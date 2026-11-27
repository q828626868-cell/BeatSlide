package com.beatslide.app.export

import android.content.Context
import android.graphics.Bitmap
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import com.beatslide.app.data.FitMode
import com.beatslide.app.data.Project
import com.beatslide.app.data.ProjectStore
import com.beatslide.app.render.BitmapLoader
import com.beatslide.app.render.SlideRenderer
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ceil
import kotlin.math.min

data class ExportSettings(
    val vertical: Boolean,
    val fps: Int,
    val startMs: Long,
    val endMs: Long,
)

class ExportCancelled : Exception()

/**
 * 用系統內建 MediaCodec / MediaMuxer 匯出 MP4（H.264 + AAC）。
 * 畫面：每一格用 SlideRenderer 依「音樂時間 = 起點 + 第幾格 / fps」畫出，與 App 內預覽完全一致。
 * 聲音：原檔是 AAC 就直接搬（不重新壓縮），其他格式（mp3 / wav…）解碼後重新壓成 AAC。
 */
object VideoExporter {
    data class Result(val width: Int, val height: Int, val frames: Int)

    private const val AVC = MediaFormat.MIMETYPE_VIDEO_AVC
    private const val AAC = MediaFormat.MIMETYPE_AUDIO_AAC
    private val AAC_RATES = intArrayOf(8000, 11025, 12000, 16000, 22050, 24000, 32000, 44100, 48000)

    fun export(
        context: Context, p: Project, s: ExportSettings, out: File,
        progress: (Float) -> Unit, cancelled: () -> Boolean,
    ): Result {
        val music = ProjectStore.file(p.id, p.musicFile ?: error("專案沒有音樂"))
        require(p.images.isNotEmpty()) { "專案沒有圖片" }
        val startUs = s.startMs * 1000L
        val endUs = s.endMs * 1000L

        // 1) 先準備好音軌（存在記憶體，幾 MB 而已），進度 0~10%
        val audio = prepareAudio(music, startUs, endUs, cancelled) { progress(it * 0.1f) }

        // 2) 影片
        val (w, h, encoderName) = chooseVideoSize(s.vertical, s.fps)
        val bitrate = if (s.fps >= 60) 16_000_000 else 10_000_000
        val vfmt = MediaFormat.createVideoFormat(AVC, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, s.fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val enc = if (encoderName != null) MediaCodec.createByCodecName(encoderName) else MediaCodec.createEncoderByType(AVC)
        var muxer: MediaMuxer? = null
        var muxStarted = false
        var gl: GlBlitter? = null
        var inputSurface: android.view.Surface? = null
        val bitmaps = SyncBitmaps(p, w, h)
        try {
            enc.configure(vfmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = enc.createInputSurface()
            enc.start()
            gl = GlBlitter(inputSurface, w, h)
            muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            var videoTrack = -1
            var audioTrack = -1
            var audioIdx = 0
            val audioInfo = MediaCodec.BufferInfo()
            val info = MediaCodec.BufferInfo()

            fun writeAudioUpTo(ptsUs: Long) {
                val m = muxer ?: return
                while (audioIdx < audio.samples.size && audio.samples[audioIdx].ptsUs <= ptsUs) {
                    val a = audio.samples[audioIdx++]
                    audioInfo.set(0, a.data.size, a.ptsUs, a.flags)
                    m.writeSampleData(audioTrack, ByteBuffer.wrap(a.data), audioInfo)
                }
            }

            fun drain(endOfStream: Boolean) {
                var waits = 0
                while (true) {
                    val idx = enc.dequeueOutputBuffer(info, if (endOfStream) 10_000 else 0)
                    when {
                        idx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                            if (!endOfStream) return
                            if (++waits > 500) error("影片編碼器沒有回應")
                        }
                        idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val m = muxer!!
                            videoTrack = m.addTrack(enc.outputFormat)
                            audioTrack = m.addTrack(audio.format)
                            m.start(); muxStarted = true
                        }
                        idx >= 0 -> {
                            val buf = enc.getOutputBuffer(idx)!!
                            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                            if (info.size > 0 && muxStarted) {
                                buf.position(info.offset); buf.limit(info.offset + info.size)
                                writeAudioUpTo(info.presentationTimeUs)
                                muxer!!.writeSampleData(videoTrack, buf, info)
                            }
                            enc.releaseOutputBuffer(idx, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                        }
                    }
                }
            }

            val renderer = SlideRenderer(context.resources.displayMetrics.density)
            val durMs = (s.endMs - s.startMs).toDouble()
            val frames = ceil(durMs * s.fps / 1000.0).toInt().coerceAtLeast(1)
            for (i in 0 until frames) {
                if (cancelled()) throw ExportCancelled()
                // 用這一格的「中間時間」來畫：換圖會落在最接近拍點的那一格（誤差 ≤ 半格）
                val t = s.startMs + (i + 0.5) * 1000.0 / s.fps
                val c = gl.canvasSurface.lockHardwareCanvas()
                try {
                    renderer.draw(c, w, h, p, t, { idx -> bitmaps.get(idx) }, showLamp = false)
                } finally {
                    gl.canvasSurface.unlockCanvasAndPost(c)
                }
                gl.publishFrame(i * 1_000_000_000L / s.fps)
                drain(false)
                if (i % 5 == 0) progress(0.1f + 0.9f * i / frames)
            }
            enc.signalEndOfInputStream()
            drain(true)
            if (!muxStarted) error("影片編碼失敗")
            writeAudioUpTo(Long.MAX_VALUE)
            progress(1f)
            return Result(w, h, frames)
        } finally {
            bitmaps.clear()
            runCatching { gl?.release() }
            runCatching { enc.stop() }
            runCatching { enc.release() }
            runCatching { inputSurface?.release() }
            runCatching { if (muxStarted) muxer?.stop() }
            runCatching { muxer?.release() }
        }
    }

    /** 找一個支援這個尺寸的 H.264 編碼器；1080p 不支援就退到 720p */
    /** 這支手機實際會用的輸出尺寸（給介面事先顯示） */
    fun outputSize(vertical: Boolean, fps: Int): Pair<Int, Int> =
        chooseVideoSize(vertical, fps).let { it.first to it.second }

    private fun chooseVideoSize(vertical: Boolean, fps: Int): Triple<Int, Int, String?> {
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        for ((a, b) in listOf(1080 to 1920, 720 to 1280)) {
            val w = if (vertical) a else b
            val h = if (vertical) b else a
            val f = MediaFormat.createVideoFormat(AVC, w, h).apply { setInteger(MediaFormat.KEY_FRAME_RATE, fps) }
            val name = runCatching { list.findEncoderForFormat(f) }.getOrNull()
            if (name != null) return Triple(w, h, name)
        }
        return if (vertical) Triple(720, 1280, null) else Triple(1280, 720, null)
    }

    /** 匯出時同步解碼圖片（只留最近用到的幾張） */
    private class SyncBitmaps(val p: Project, val w: Int, val h: Int) {
        private val map = LinkedHashMap<Int, Bitmap>(8, 0.75f, true)
        fun get(idx: Int): Bitmap? {
            map[idx]?.let { return it }
            val name = p.images.getOrNull(idx) ?: return null
            val b = BitmapLoader.decode(ProjectStore.file(p.id, name), w, h, p.fitMode == FitMode.FILL) ?: return null
            map[idx] = b
            while (map.size > 4) map.remove(map.keys.first())
            return b
        }
        fun clear() = map.clear()
    }

    // ------------------------------------------------------------------ 音訊

    private class Sample(val data: ByteArray, val ptsUs: Long, val flags: Int)
    private class AudioData(val format: MediaFormat, val samples: List<Sample>)

    private fun prepareAudio(file: File, startUs: Long, endUs: Long, cancelled: () -> Boolean, progress: (Float) -> Unit): AudioData {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(file.absolutePath)
            var track = -1
            for (i in 0 until ex.trackCount) {
                if (ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) { track = i; break }
            }
            check(track >= 0) { "音樂檔裡找不到音軌" }
            val fmt = ex.getTrackFormat(track)
            ex.selectTrack(track)
            // 不 seek：從頭解碼並「數取樣數」決定時間。部分格式（例如 m4a）解碼器回報的時間戳記
            // 會因為開頭的 priming 而偏移一格，數取樣數才跟播放器聽到的時間一致。解碼很快，影響不大。
            // 一律解碼後重新壓成 AAC：這樣才能精確補償 AAC 編碼器在開頭加的延遲（不同手機不一樣）
            return transcode(ex, fmt, startUs, endUs, cancelled, progress)
        } finally {
            ex.release()
        }
    }

    private fun transcode(ex: MediaExtractor, fmt: MediaFormat, startUs: Long, endUs: Long, cancelled: () -> Boolean, progress: (Float) -> Unit): AudioData {
        val dec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
        var enc: MediaCodec? = null
        val samples = ArrayList<Sample>()
        var outFormat: MediaFormat? = null
        try {
            dec.configure(fmt, null, null, 0)
            dec.start()
            var srcRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var srcCh = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var floatPcm = false
            var srcFrames = 0L   // 已解碼的取樣數（每聲道）
            var outRate = 0; var outCh = 0
            var resampler: Resampler? = null
            var bytesQueued = 0L
            var shiftUs = 0L
            val encInfo = MediaCodec.BufferInfo()

            fun drainEnc(eos: Boolean) {
                val e = enc ?: return
                var waits = 0
                while (true) {
                    val idx = e.dequeueOutputBuffer(encInfo, if (eos) 10_000 else 0)
                    when {
                        idx == MediaCodec.INFO_TRY_AGAIN_LATER -> { if (!eos) return; if (++waits > 500) error("音訊編碼器沒有回應") }
                        idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> outFormat = e.outputFormat
                        idx >= 0 -> {
                            val b = e.getOutputBuffer(idx)!!
                            val pts = encInfo.presentationTimeUs - shiftUs
                            // 開頭「編碼器延遲」那幾格丟掉，讓聲音和畫面對齊
                            if (encInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && encInfo.size > 0 && pts > -500) {
                                val arr = ByteArray(encInfo.size)
                                b.position(encInfo.offset); b.get(arr)
                                samples.add(Sample(arr, pts.coerceAtLeast(0), MediaCodec.BUFFER_FLAG_KEY_FRAME))
                            }
                            e.releaseOutputBuffer(idx, false)
                            if (encInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                        }
                    }
                }
            }

            fun feed(pcm: ShortArray) {
                val e = enc!!
                val bytes = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.nativeOrder())
                bytes.asShortBuffer().put(pcm)
                val arr = bytes.array()
                var off = 0
                while (off < arr.size) {
                    val ii = e.dequeueInputBuffer(5_000)
                    if (ii >= 0) {
                        val ib = e.getInputBuffer(ii)!!
                        ib.clear()
                        val n = min(ib.remaining(), arr.size - off)
                        ib.put(arr, off, n)
                        val pts = bytesQueued / (2L * outCh) * 1_000_000L / outRate
                        e.queueInputBuffer(ii, 0, n, pts, 0)
                        bytesQueued += n; off += n
                    }
                    drainEnc(false)
                }
            }

            fun ensureEncoder() {
                if (enc != null) return
                outRate = if (srcRate in AAC_RATES) srcRate else 44100
                outCh = min(srcCh, 2).coerceAtLeast(1)
                if (outRate != srcRate) resampler = Resampler(srcRate, outRate, outCh)
                // 量這支手機 AAC 編碼器的開頭延遲，補成 1024 的倍數後整格丟掉
                val delay = measureAacDelay(outRate, outCh) ?: 2048
                lastMeasuredDelay = delay
                android.util.Log.i("BeatSlide", "AAC encoder delay = $delay samples @ $outRate Hz")
                val pad = (AAC_FRAME - delay % AAC_FRAME) % AAC_FRAME
                shiftUs = (delay + pad).toLong() * 1_000_000L / outRate
                enc = newAacEncoder(outRate, outCh)
                if (pad > 0) feed(ShortArray(pad * outCh))
            }

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var done = false
            while (!done) {
                if (cancelled()) throw ExportCancelled()
                if (!inputDone) {
                    val ii = dec.dequeueInputBuffer(5_000)
                    if (ii >= 0) {
                        val ib = dec.getInputBuffer(ii)!!
                        val n = ex.readSampleData(ib, 0)
                        if (n < 0 || ex.sampleTime > endUs + 200_000) {
                            dec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true
                        } else {
                            dec.queueInputBuffer(ii, 0, n, ex.sampleTime, 0); ex.advance()
                        }
                    }
                }
                val oi = dec.dequeueOutputBuffer(info, 5_000)
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val of = dec.outputFormat
                    srcRate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    srcCh = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    floatPcm = of.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                        of.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                } else if (oi >= 0) {
                    ensureEncoder()
                    val b = dec.getOutputBuffer(oi)!!
                    b.position(info.offset); b.limit(info.offset + info.size)
                    val ob = b.order(ByteOrder.nativeOrder())
                    val frames = info.size / ((if (floatPcm) 4 else 2) * srcCh)
                    val pcm = ShortArray(frames * outCh)
                    var k = 0
                    val fb = if (floatPcm) ob.asFloatBuffer() else null
                    val sb = if (!floatPcm) ob.asShortBuffer() else null
                    val base = srcFrames
                    srcFrames += frames
                    for (f in 0 until frames) {
                        val tUs = (base + f) * 1_000_000L / srcRate
                        for (c in 0 until srcCh) {
                            val v: Short = if (fb != null) (fb.get().coerceIn(-1f, 1f) * 32767f).toInt().toShort() else sb!!.get()
                            if (c < outCh && tUs >= startUs && tUs < endUs) pcm[k++] = v
                        }
                        if (tUs >= endUs) { done = true; break }
                    }
                    dec.releaseOutputBuffer(oi, false)
                    if (k > 0) {
                        val chunk = if (k == pcm.size) pcm else pcm.copyOf(k)
                        feed(resampler?.process(chunk) ?: chunk)
                    }
                    progress((srcFrames * 1_000_000f / srcRate / endUs).coerceIn(0f, 1f))
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) done = true
                }
            }
            ensureEncoder()
            val e = enc!!
            while (true) {
                val ii = e.dequeueInputBuffer(10_000)
                if (ii >= 0) { e.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); break }
                drainEnc(false)
            }
            drainEnc(true)
            return AudioData(outFormat ?: error("音訊轉檔失敗"), samples)
        } finally {
            runCatching { dec.stop() }; runCatching { dec.release() }
            runCatching { enc?.stop() }; runCatching { enc?.release() }
        }
    }

    private const val AAC_FRAME = 1024

    /** 最近一次量到的 AAC 延遲（取樣數），顯示在結果裡方便除錯 */
    @Volatile var lastMeasuredDelay: Int? = null

    private fun newAacEncoder(rate: Int, ch: Int): MediaCodec {
        val af = MediaFormat.createAudioFormat(AAC, rate, ch).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 192_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
        }
        return MediaCodec.createEncoderByType(AAC).apply { configure(af, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); start() }
    }

    /**
     * 實際量測「AAC 編碼 → 解碼」後聲音被往後推了幾個取樣：
     * 編一個短促的喀聲，再解碼回來找它的位置。不同手機的編碼器延遲不一樣，所以每次匯出都量。
     * 失敗回傳 null（呼叫端改用常見值 2048）。
     */
    private fun measureAacDelay(rate: Int, ch: Int): Int? = runCatching {
        val total = 16 * AAC_FRAME
        val at = 6 * AAC_FRAME + 300
        val pcm = ShortArray(total * ch)
        for (k in -6..6) {
            val v = (20000 * (1.0 - kotlin.math.abs(k) / 7.0)).toInt().toShort()
            for (c in 0 until ch) pcm[(at + k) * ch + c] = v
        }
        val info = MediaCodec.BufferInfo()

        // 編碼
        val enc = newAacEncoder(rate, ch)
        val frames = ArrayList<Pair<ByteArray, Long>>()
        var fmtOut: MediaFormat? = null
        try {
            val bytes = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.nativeOrder())
                .also { it.asShortBuffer().put(pcm) }.array()
            var off = 0; var eosSent = false; var done = false; var guard = 0
            while (!done && guard++ < 5000) {
                if (!eosSent) {
                    val ii = enc.dequeueInputBuffer(5_000)
                    if (ii >= 0) {
                        val ib = enc.getInputBuffer(ii)!!; ib.clear()
                        if (off >= bytes.size) {
                            enc.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); eosSent = true
                        } else {
                            val n = min(ib.remaining(), bytes.size - off); ib.put(bytes, off, n)
                            enc.queueInputBuffer(ii, 0, n, off / (2L * ch) * 1_000_000L / rate, 0); off += n
                        }
                    }
                }
                val oi = enc.dequeueOutputBuffer(info, 5_000)
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) fmtOut = enc.outputFormat
                else if (oi >= 0) {
                    val b = enc.getOutputBuffer(oi)!!
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                        val arr = ByteArray(info.size); b.position(info.offset); b.get(arr)
                        frames.add(arr to info.presentationTimeUs)
                    }
                    enc.releaseOutputBuffer(oi, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) done = true
                }
            }
        } finally { runCatching { enc.stop() }; enc.release() }

        // 解碼
        val dec = MediaCodec.createDecoderByType(AAC)
        val out = ShortArray(total * ch * 2)
        var outN = 0
        try {
            dec.configure(fmtOut!!, null, null, 0); dec.start()
            var idx = 0; var eosSent = false; var done = false; var guard = 0; var floatOut = false
            while (!done && guard++ < 5000) {
                if (!eosSent) {
                    val ii = dec.dequeueInputBuffer(5_000)
                    if (ii >= 0) {
                        val ib = dec.getInputBuffer(ii)!!; ib.clear()
                        if (idx >= frames.size) {
                            dec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); eosSent = true
                        } else {
                            val (d, t) = frames[idx++]; ib.put(d); dec.queueInputBuffer(ii, 0, d.size, t, 0)
                        }
                    }
                }
                val oi = dec.dequeueOutputBuffer(info, 5_000)
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val of = dec.outputFormat
                    floatOut = of.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                        of.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                } else if (oi >= 0) {
                    val b = dec.getOutputBuffer(oi)!!.order(ByteOrder.nativeOrder())
                    b.position(info.offset); b.limit(info.offset + info.size)
                    if (floatOut) {
                        val fb = b.asFloatBuffer()
                        while (fb.hasRemaining() && outN < out.size) out[outN++] = (fb.get() * 32767f).toInt().toShort()
                    } else {
                        val sb = b.asShortBuffer()
                        while (sb.hasRemaining() && outN < out.size) out[outN++] = sb.get()
                    }
                    dec.releaseOutputBuffer(oi, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) done = true
                }
            }
        } finally { runCatching { dec.stop() }; dec.release() }

        // 找喀聲位置（只看第一聲道）
        var best = -1; var bestV = 0
        var i = 0
        while (i < outN) {
            val v = kotlin.math.abs(out[i].toInt())
            if (v > bestV) { bestV = v; best = i / ch }
            i += ch
        }
        val d = best - at
        if (bestV < 2000 || d < 0 || d > 8 * AAC_FRAME) null else d
    }.getOrNull()

    /** 簡單的線性插值重新取樣（只有原檔取樣率不是 AAC 支援的才會用到，例如 96kHz wav） */
    private class Resampler(inRate: Int, outRate: Int, private val ch: Int) {
        private val step = inRate.toDouble() / outRate
        private var pos = 0.0
        private val prev = ShortArray(ch)
        fun process(inp: ShortArray): ShortArray {
            val n = inp.size / ch
            if (n == 0) return ShortArray(0)
            val out = ShortArray(((n + 2) / step).toInt() * ch + ch)
            var k = 0
            while (pos < n - 1 && k + ch <= out.size) {
                val i = kotlin.math.floor(pos).toInt(); val f = pos - i
                for (c in 0 until ch) {
                    val a = if (i < 0) prev[c] else inp[i * ch + c]
                    val b = inp[(i + 1) * ch + c]
                    out[k++] = (a + (b - a) * f).toInt().toShort()
                }
                pos += step
            }
            pos -= n
            for (c in 0 until ch) prev[c] = inp[(n - 1) * ch + c]
            return out.copyOf(k)
        }
    }
}
