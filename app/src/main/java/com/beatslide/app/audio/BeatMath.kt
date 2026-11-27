package com.beatslide.app.audio

import com.beatslide.app.data.Project
import kotlin.math.floor

/** 某個時間點的拍子狀態，全部由「音樂位置」推算 */
class BeatInfo(
    /** 尚未到第一拍（offset 之前） */
    val beforeStart: Boolean,
    /** 第幾拍（從 0 起算，offset 之前為 -1） */
    val beatIndex: Long,
    /** 距離目前這一拍過了幾毫秒 */
    val sinceBeatMs: Double,
    /** 這一拍在小節中的位置（0 = 重拍） */
    val beatInBar: Int,
    /** 第幾次換圖（從 0 起算） */
    val slideNum: Long,
    /** 距離這次換圖過了幾毫秒 */
    val sinceChangeMs: Double,
    /** 一張圖停留多久 */
    val slideLenMs: Double,
    val beatLenMs: Double,
    /** 這次換圖是否落在小節第一拍 */
    val changeOnDownbeat: Boolean,
)

object BeatMath {
    fun beatLenMs(bpm: Double) = 60000.0 / bpm.coerceIn(Project.MIN_BPM, Project.MAX_BPM)

    fun compute(p: Project, timeMs: Double): BeatInfo {
        val beatLen = beatLenMs(p.bpm)
        val bps = p.beatsPerSlide.coerceAtLeast(1)
        val bpb = p.beatsPerBar.coerceAtLeast(1)
        val slideLen = beatLen * bps
        val rel = timeMs - p.offsetMs
        if (rel < 0) {
            return BeatInfo(true, -1, 0.0, 0, 0, -1.0, slideLen, beatLen, false)
        }
        val beatPos = rel / beatLen
        val beat = floor(beatPos).toLong()
        val slide = beat / bps
        val slideStartBeat = slide * bps
        return BeatInfo(
            beforeStart = false,
            beatIndex = beat,
            sinceBeatMs = rel - beat * beatLen,
            beatInBar = (beat % bpb).toInt(),
            slideNum = slide,
            sinceChangeMs = rel - slideStartBeat * beatLen,
            slideLenMs = slideLen,
            beatLenMs = beatLen,
            changeOnDownbeat = slideStartBeat % bpb == 0L,
        )
    }

    fun imageIndex(slideNum: Long, count: Int): Int =
        if (count <= 0) -1 else (((slideNum % count) + count) % count).toInt()
}
