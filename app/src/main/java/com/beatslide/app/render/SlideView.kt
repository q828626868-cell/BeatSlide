package com.beatslide.app.render

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.view.View
import com.beatslide.app.audio.BeatMath
import com.beatslide.app.data.FitMode
import com.beatslide.app.data.Project
import com.beatslide.app.data.ProjectStore
import java.io.File

/** 幻燈片畫面。每次 vsync 重畫一次（約 60fps），時間一律取自 timeSource（音樂位置）。 */
@SuppressLint("ViewConstructor")
class SlideView(context: Context, private val loader: BitmapLoader) : View(context) {
    var project: Project? = null
    var timeSource: () -> Double = { 0.0 }
    /** 拖動進度條時用來覆蓋時間，讓畫面立刻跳到該時間點 */
    var overrideTime: Double? = null
    var showLamp = true

    private val renderer = SlideRenderer(resources.displayMetrics.density)
    private var filesKey: List<String>? = null
    private var files: Array<File> = emptyArray()

    private fun filesFor(p: Project): Array<File> {
        if (filesKey !== p.images) {
            files = Array(p.images.size) { ProjectStore.file(p.id, p.images[it]) }
            filesKey = p.images
        }
        return files
    }
    private val onLoaded: () -> Unit = { invalidate() }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        loader.addListener(onLoaded)
    }

    override fun onDetachedFromWindow() {
        loader.removeListener(onLoaded)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val p = project
        if (p == null) { canvas.drawColor(android.graphics.Color.BLACK); return }
        val w = width; val h = height
        val fill = p.fitMode == FitMode.FILL
        val t = overrideTime ?: timeSource()
        val fs = filesFor(p)
        renderer.draw(canvas, w, h, p, t, { idx ->
            fs.getOrNull(idx)?.let { loader.get(it, w, h, fill) }
        }, showLamp)

        // 預先載入接下來的圖片
        val info = renderer.lastInfo
        val n = p.images.size
        if (info != null && n > 1) {
            for (d in intArrayOf(1, 2, -1)) {
                val i = BeatMath.imageIndex(info.slideNum + d, n)
                fs.getOrNull(i)?.let { loader.prefetch(it, w, h, fill) }
            }
        }
        if (isShown) postInvalidateOnAnimation()
    }
}
