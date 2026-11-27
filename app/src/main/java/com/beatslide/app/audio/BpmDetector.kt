package com.beatslide.app.audio

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min

/**
 * 自動偵測 BPM（只當建議值）：
 * 1. MediaExtractor + MediaCodec 把音訊解碼成 PCM（系統內建，不需要第三方套件）
 * 2. 每 5ms 算一次能量（全頻 + 低頻），取對數差分得到「起音強度」曲線
 * 3. 自相關找出最可能的拍長（偏好 80~160 BPM）
 * 4. 在附近以 0.01 BPM 精細搜尋，同時找出最對齊的相位 → 第一拍位置
 */
object BpmDetector {
    data class Result(val bpm: Double, val offsetMs: Long, val confidence: Float)

    private const val FPS = 200.0          // 起音曲線取樣率（每格 5ms）
    private const val MAX_SECONDS = 150

    fun detect(file: File, progress: (Float) -> Unit = {}): Result? {
        val (env, fps) = decodeEnvelope(file, progress) ?: return null
        if (env.size < FPS * 8) return null
        val onset = onsetCurve(env)
        progress(0.85f)
        return estimate(onset, fps).also { progress(1f) }
    }

    /** 回傳每格 [全頻能量, 低頻能量] 交錯的陣列，以及實際的每秒格數（取樣率 / hop 不一定整除） */
    private fun decodeEnvelope(file: File, progress: (Float) -> Unit): Pair<FloatArray, Double>? {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            ex.setDataSource(file.absolutePath)
            var track = -1
            for (i in 0 until ex.trackCount) {
                if (ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) { track = i; break }
            }
            if (track < 0) return null
            val fmt = ex.getTrackFormat(track)
            ex.selectTrack(track)
            val mime = fmt.getString(MediaFormat.KEY_MIME)!!
            var sampleRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val durUs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else 0L
            val totalUs = min(durUs.takeIf { it > 0 } ?: Long.MAX_VALUE, MAX_SECONDS * 1_000_000L)
            var floatPcm = false

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(fmt, null, null, 0)
            codec.start()

            val out = FloatArrayList()
            var hop = (sampleRate / FPS).toInt()
            var lpAlpha = lpAlpha(sampleRate)
            var lp = 0.0
            var accFull = 0.0; var accLow = 0.0; var accN = 0
            var samplesDone = 0L
            val maxSamples = { sampleRate.toLong() * MAX_SECONDS }

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            while (!outputDone) {
                if (!inputDone) {
                    val ii = codec.dequeueInputBuffer(5000)
                    if (ii >= 0) {
                        val buf = codec.getInputBuffer(ii)!!
                        val n = ex.readSampleData(buf, 0)
                        if (n < 0 || ex.sampleTime > totalUs) {
                            codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(ii, 0, n, ex.sampleTime, 0)
                            if (totalUs != Long.MAX_VALUE) progress(0.8f * ex.sampleTime / totalUs)
                            ex.advance()
                        }
                    }
                }
                val oi = codec.dequeueOutputBuffer(info, 5000)
                when {
                    oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val of = codec.outputFormat
                        sampleRate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        floatPcm = of.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                            of.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                        hop = (sampleRate / FPS).toInt()
                        lpAlpha = lpAlpha(sampleRate)
                    }
                    oi >= 0 -> {
                        val buf = codec.getOutputBuffer(oi)!!
                        buf.position(info.offset); buf.limit(info.offset + info.size)
                        val ob = buf.order(ByteOrder.nativeOrder())
                        val ch = max(1, channels)
                        if (floatPcm) {
                            val fb = ob.asFloatBuffer()
                            val frames = fb.remaining() / ch
                            for (f in 0 until frames) {
                                var s = 0.0
                                for (c in 0 until ch) s += fb.get()
                                s /= ch
                                lp += lpAlpha * (s - lp)
                                accFull += s * s; accLow += lp * lp
                                if (++accN >= hop) { out.add(accFull.toFloat()); out.add(accLow.toFloat()); accFull = 0.0; accLow = 0.0; accN = 0 }
                            }
                            samplesDone += frames
                        } else {
                            val sb = ob.asShortBuffer()
                            val frames = sb.remaining() / ch
                            for (f in 0 until frames) {
                                var s = 0.0
                                for (c in 0 until ch) s += sb.get()
                                s /= (ch * 32768.0)
                                lp += lpAlpha * (s - lp)
                                accFull += s * s; accLow += lp * lp
                                if (++accN >= hop) { out.add(accFull.toFloat()); out.add(accLow.toFloat()); accFull = 0.0; accLow = 0.0; accN = 0 }
                            }
                            samplesDone += frames
                        }
                        codec.releaseOutputBuffer(oi, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0 || samplesDone >= maxSamples()) outputDone = true
                    }
                }
            }
            return out.toArray() to sampleRate.toDouble() / hop
        } catch (e: Exception) {
            return null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            ex.release()
        }
    }

    private fun lpAlpha(sr: Int) = (1.0 - exp(-2.0 * PI * 150.0 / sr))

    /** 起音強度：對數能量的正向差分，再扣掉局部平均 */
    private fun onsetCurve(env: FloatArray): FloatArray {
        val n = env.size / 2
        val raw = FloatArray(n)
        var pf = 0.0; var pl = 0.0
        for (i in 0 until n) {
            val f = ln(1e-6 + env[2 * i]); val l = ln(1e-6 + env[2 * i + 1])
            if (i > 0) raw[i] = (max(0.0, f - pf) + 1.2 * max(0.0, l - pl)).toFloat()
            pf = f; pl = l
        }
        // 平滑（約 15ms）
        val sm = FloatArray(n)
        for (i in 0 until n) {
            var s = 0f; var c = 0
            for (k in -2..2) { val j = i + k; if (j in 0 until n) { s += raw[j] * (3 - kotlin.math.abs(k)); c += 3 - kotlin.math.abs(k) } }
            sm[i] = s / c
        }
        // 扣除 0.5 秒滑動平均
        val w = (FPS * 0.25).toInt()
        val prefix = DoubleArray(n + 1)
        for (i in 0 until n) prefix[i + 1] = prefix[i] + sm[i]
        val res = FloatArray(n)
        for (i in 0 until n) {
            val a = max(0, i - w); val b = min(n, i + w + 1)
            val mean = (prefix[b] - prefix[a]) / (b - a)
            res[i] = max(0.0, sm[i] - mean).toFloat()
        }
        return res
    }

    private fun estimate(o: FloatArray, fps: Double): Result? {
        val n = o.size
        val minBpm = 60.0; val maxBpm = 200.0
        val minLag = (60.0 * fps / maxBpm).toInt()
        val maxLag = (60.0 * fps / minBpm).toInt() + 1
        val ac = DoubleArray(maxLag * 2 + 2)
        for (lag in minLag..min(maxLag * 2 + 1, n - 1)) {
            var s = 0.0
            for (i in 0 until n - lag) s += o[i] * o[i + lag]
            ac[lag] = s / (n - lag)
        }
        // 加權（偏好 120 BPM 附近）+ 倍數拍強化
        var bestLag = -1; var bestScore = -1.0
        for (lag in minLag..maxLag) {
            val bpm = 60.0 * fps / lag
            val w = exp(-0.5 * (log2(bpm / 120.0) / 0.9).let { it * it })
            val l2 = lag * 2
            val score = w * (ac[lag] + if (l2 < ac.size) 0.5 * ac[l2] else 0.0)
            if (score > bestScore) { bestScore = score; bestLag = lag }
        }
        if (bestLag < 0) return null
        val coarse = 60.0 * fps / bestLag

        // 精細搜尋：BPM ±3%，0.01 步進；同時找相位
        var best = coarse; var bestPhase = 0.0; var bestVal = -1.0; var meanVal = 0.0; var count = 0
        var bpm = coarse * 0.97
        while (bpm <= coarse * 1.03) {
            val period = 60.0 * fps / bpm
            val (ph, v, avg) = combScore(o, period)
            meanVal += avg; count++
            if (v > bestVal) { bestVal = v; best = bpm; bestPhase = ph }
            bpm += 0.01
        }
        meanVal /= max(1, count)
        val conf = if (meanVal > 0) ((bestVal / meanVal - 1.0) / 1.5).coerceIn(0.0, 1.0).toFloat() else 0f
        val rounded = Math.round(best * 10.0) / 10.0
        val offsetMs = Math.round(bestPhase / fps * 1000.0)
        return Result(rounded, offsetMs, conf)
    }

    /** 對某個拍長，找出所有拍點起音總和最大的相位。回傳 (相位, 最大分數, 平均分數) */
    private fun combScore(o: FloatArray, period: Double): Triple<Double, Double, Double> {
        val n = o.size
        val steps = period.toInt().coerceAtLeast(1)
        var bestPh = 0.0; var bestV = -1.0; var sumV = 0.0
        for (p in 0 until steps) {
            var s = 0.0; var c = 0
            var t = p.toDouble()
            while (t < n - 1) {
                val i = t.toInt(); val f = t - i
                s += o[i] * (1 - f) + o[i + 1] * f
                c++; t += period
            }
            val v = if (c > 0) s / c else 0.0
            sumV += v
            if (v > bestV) { bestV = v; bestPh = p.toDouble() }
        }
        return Triple(bestPh, bestV, sumV / steps)
    }

    private class FloatArrayList {
        private var a = FloatArray(1 shl 16); private var n = 0
        fun add(v: Float) { if (n == a.size) a = a.copyOf(a.size * 2); a[n++] = v }
        fun toArray() = a.copyOf(n)
    }
}
