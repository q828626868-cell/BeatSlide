package com.beatslide.app.audio

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.MediaTimestamp
import java.io.File
import kotlin.math.abs

/**
 * MediaPlayer 包裝。positionMs() 是整個 App 的「時間來源」：
 * 每一格畫面都用音樂目前的播放位置去算拍子，不用計時器累加，所以不會越播越偏。
 *
 * getCurrentPosition() 在部分手機上更新很粗，所以優先用 MediaTimestamp
 * （音訊硬體回報的錨點 + 系統時鐘）推算精確位置，並用 currentPosition 校驗。
 */
class MusicPlayer {
    private var mp: MediaPlayer? = null
    private var loadedPath: String? = null

    var durationMs: Long = 0; private set
    @Volatile var isPlaying = false; private set
    var onStateChanged: (() -> Unit)? = null

    private var seekTarget: Double? = null
    private var ts: MediaTimestamp? = null
    private var tsFetchedAt = 0L
    private var lastPos = 0.0

    fun load(file: File?) {
        val path = file?.absolutePath
        if (path == loadedPath) return
        release()
        loadedPath = path
        if (file == null || !file.exists()) return
        mp = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
            )
            setDataSource(path)
            isLooping = true
            setOnSeekCompleteListener { seekTarget = null; ts = null }
            prepare()
        }
        durationMs = mp?.duration?.toLong() ?: 0
        lastPos = 0.0
    }

    val isLoaded get() = mp != null

    fun play() {
        val p = mp ?: return
        p.start(); isPlaying = true; ts = null
        onStateChanged?.invoke()
    }

    fun pause() {
        val p = mp ?: return
        if (p.isPlaying) p.pause()
        isPlaying = false; ts = null
        lastPos = p.currentPosition.toDouble()
        onStateChanged?.invoke()
    }

    fun toggle() = if (isPlaying) pause() else play()

    fun seekTo(ms: Double) {
        val p = mp ?: return
        val t = ms.coerceIn(0.0, durationMs.toDouble())
        seekTarget = t
        lastPos = t
        ts = null
        p.seekTo(t.toLong(), MediaPlayer.SEEK_CLOSEST)
    }

    /** 目前音樂播放位置（毫秒，含小數） */
    fun positionMs(): Double {
        val p = mp ?: return 0.0
        seekTarget?.let { return it }
        if (!isPlaying) return p.currentPosition.toDouble()

        val now = System.nanoTime()
        val cur = p.currentPosition.toDouble()
        if (ts == null || now - tsFetchedAt > 250_000_000L) {
            ts = runCatching { p.timestamp }.getOrNull()
            tsFetchedAt = now
        }
        var pos = cur
        val t = ts
        if (t != null && t.mediaClockRate > 0f) {
            val est = (t.anchorMediaTimeUs + (now - t.anchorSystemNanoTime) / 1000.0 * t.mediaClockRate) / 1000.0
            // 估計值跟 currentPosition 差太多（例如剛循環回開頭）就不採用
            if (abs(est - cur) < 120.0) pos = est else ts = null
        }
        // 避免極小的倒退抖動
        if (pos < lastPos && lastPos - pos < 40.0) pos = lastPos
        lastPos = pos
        return pos
    }

    fun release() {
        mp?.release()
        mp = null
        loadedPath = null
        isPlaying = false
        durationMs = 0
    }
}
