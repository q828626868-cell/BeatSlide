package com.beatslide.app.render

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import java.io.File
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors

/**
 * 背景執行緒解碼圖片，依畫面大小縮小解碼，並快取最近用到的幾張。
 * 優先解成 HARDWARE bitmap：上傳到 GPU 的動作在背景完成，換圖瞬間不會卡頓。
 */
class BitmapLoader(maxBytes: Int = defaultMaxBytes()) {
    private val cache = object : LruCache<String, Bitmap>(maxBytes) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    private val pending = HashSet<String>()
    private val executor = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    @Volatile private var closed = false

    fun addListener(l: () -> Unit) = listeners.add(l)
    fun removeListener(l: () -> Unit) = listeners.remove(l)

    private fun key(f: File, w: Int, h: Int, fill: Boolean) = "${f.absolutePath}|${w}x$h|$fill"

    /** 取得已解碼的圖片；尚未解碼就排入背景並回傳 null */
    fun get(f: File, w: Int, h: Int, fill: Boolean): Bitmap? {
        if (w <= 0 || h <= 0) return null
        val k = key(f, w, h, fill)
        cache.get(k)?.let { return it }
        request(f, w, h, fill, k)
        return null
    }

    fun prefetch(f: File, w: Int, h: Int, fill: Boolean) {
        if (w <= 0 || h <= 0) return
        val k = key(f, w, h, fill)
        if (cache.get(k) == null) request(f, w, h, fill, k)
    }

    private fun request(f: File, w: Int, h: Int, fill: Boolean, k: String) {
        synchronized(pending) { if (!pending.add(k)) return }
        executor.execute {
            val bmp = if (closed) null else runCatching { decode(f, w, h, fill) }.getOrNull()
            synchronized(pending) { pending.remove(k) }
            if (bmp != null && !closed) {
                cache.put(k, bmp)
                main.post { listeners.forEach { it() } }
            }
        }
    }

    fun close() {
        closed = true
        executor.shutdownNow()
        cache.evictAll()
        listeners.clear()
    }

    companion object {
        fun defaultMaxBytes(): Int =
            (Runtime.getRuntime().maxMemory() / 4).coerceAtMost(160L * 1024 * 1024).toInt()

        fun decode(f: File, reqW: Int, reqH: Int, fill: Boolean, allowHardware: Boolean = true): Bitmap? {
            val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.absolutePath, b)
            if (b.outWidth <= 0) return null
            var sample = 1
            while (true) {
                val nw = b.outWidth / (sample * 2); val nh = b.outHeight / (sample * 2)
                // 留一點餘裕給 Ken Burns / 縮放特效
                val ok = if (fill) nw >= reqW * 1.15 && nh >= reqH * 1.15 else nw >= reqW * 1.15 || nh >= reqH * 1.15
                if (!ok) break
                sample *= 2
            }
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = if (allowHardware) Bitmap.Config.HARDWARE else Bitmap.Config.ARGB_8888
            }
            return BitmapFactory.decodeFile(f.absolutePath, opts)
                ?: BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    }
}
