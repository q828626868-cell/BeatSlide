package com.beatslide.app.render

import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.os.Build
import com.beatslide.app.audio.BeatInfo
import com.beatslide.app.audio.BeatMath
import com.beatslide.app.data.Effect
import com.beatslide.app.data.FitMode
import com.beatslide.app.data.Project
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * 依「時間點」畫出一格畫面。所有狀態都由 (專案設定, 音樂位置) 推算，
 * 沒有任何累加的內部狀態，所以暫停、拖動進度條、循環播放都會畫出正確的畫面。
 * （第二階段匯出 MP4 時也會重用這個類別，一格一格畫到編碼器的 Surface 上。）
 */
class SlideRenderer(private val density: Float) {
    private val effects = Effect.entries.toTypedArray()
    private val fx = FloatArray(effects.size)
    private val enabledBuf = IntArray(effects.size)

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val blackPaint = Paint().apply { color = Color.BLACK }
    private val overlayPaint = Paint()
    private val lampPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val lampRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.5f * density; color = Color.argb(140, 255, 255, 255)
    }
    private val channelPaints = Array(3) { ch ->
        Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply {
            val m = FloatArray(20); m[ch * 5 + ch] = 1f; m[18] = 1f
            colorFilter = ColorMatrixColorFilter(ColorMatrix(m))
            if (Build.VERSION.SDK_INT >= 29) blendMode = BlendMode.PLUS
            else xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
        }
    }
    private val dst = RectF()
    private val tmp = RectF()
    private val bands = FloatArray(16 * 3)
    private var bandCount = 0
    private var lastBitmap: Bitmap? = null

    /** 最近一次畫的拍子資訊（給外部做預先載入、顯示用） */
    var lastInfo: BeatInfo? = null; private set

    fun draw(
        c: Canvas, w: Int, h: Int, p: Project, timeMs: Double,
        provider: (Int) -> Bitmap?, showLamp: Boolean,
    ) {
        c.drawColor(Color.BLACK)
        val info = BeatMath.compute(p, timeMs)
        lastInfo = info
        val n = p.images.size
        if (n > 0 && w > 0 && h > 0) drawSlides(c, w, h, p, info, provider, n)
        if (showLamp) drawLamp(c, w, info, p)
    }

    private fun drawSlides(c: Canvas, w: Int, h: Int, p: Project, info: BeatInfo, provider: (Int) -> Bitmap?, n: Int) {
        val idx = BeatMath.imageIndex(info.slideNum, n)
        val cur = provider(idx) ?: lastBitmap ?: return
        lastBitmap = cur
        computeFx(p, info)
        val since = info.sinceChangeMs
        val slideLen = info.slideLenMs
        val cap = slideLen * 0.85
        fun prog(baseMs: Float): Float =
            if (info.beforeStart) 1f else (since / min(baseMs.toDouble(), cap)).toFloat().coerceIn(0f, 1f)

        // --- Ken Burns（持續作用） ---
        val kbQ = if (info.beforeStart) 0f else (since / slideLen).toFloat().coerceIn(0f, 1f)
        kenBurns(p, info.slideNum, kbQ, w, h)
        var s = kbS; var tx = kbX; var ty = kbY

        // --- 交叉淡入淡出：先畫上一張 ---
        var alpha = 255
        val cf = fx[Effect.CROSSFADE.ordinal]
        if (cf > 0f && !info.beforeStart && info.slideNum > 0 && n > 1) {
            val d = min(120.0 + 600.0 * cf, slideLen * 0.8)
            val q = (since / d).toFloat()
            if (q < 1f) {
                val prev = provider(BeatMath.imageIndex(info.slideNum - 1, n))
                if (prev != null) {
                    kenBurns(p, info.slideNum - 1, 1f, w, h)
                    drawImage(c, prev, w, h, p.fitMode, kbS, kbX, kbY, 255, 0f, false)
                    alpha = (smooth(q) * 255).toInt()
                }
            }
        }

        // --- 縮放衝擊 ---
        val zi = fx[Effect.ZOOM_PUNCH.ordinal]
        if (zi > 0f) {
            val q = prog(180f + 120f * zi)
            if (q < 1f) s *= 1f + (0.03f + 0.12f * zi) * (1f - q).pow(3)
        }

        // --- 震動 ---
        val si = fx[Effect.SHAKE.ordinal]
        if (si > 0f) {
            val q = prog(200f + 200f * si)
            if (q < 1f) {
                val a = min(w, h) * (0.008f + 0.035f * si) * (1f - q) * (1f - q)
                val seed = (mix(info.slideNum) % 1000) / 100f
                tx += a * sin(since * 0.15 + seed).toFloat()
                ty += a * cos(since * 0.12 + seed * 1.7).toFloat()
                s *= 1f + 2f * a / min(w, h)   // 稍微放大，避免露出黑邊
            }
        }

        // --- RGB 色偏 ---
        var rgbOff = 0f
        val ri = fx[Effect.RGB_SPLIT.ordinal]
        if (ri > 0f) {
            val q = prog(200f + 300f * ri)
            if (q < 1f) {
                val dir = if (mix(info.slideNum) and 1L == 0L) 1f else -1f
                rgbOff = dir * w * (0.004f + 0.03f * ri) * (1f - q).pow(1.5f)
            }
        }

        // --- 故障切片 ---
        var glitch = false
        val gi = fx[Effect.GLITCH.ordinal]
        if (gi > 0f) {
            val q = prog(150f + 300f * gi)
            if (q < 1f) glitch = buildBands(info.slideNum, since, gi, q, w, h)
        }

        drawImage(c, cur, w, h, p.fitMode, s, tx, ty, alpha, rgbOff, glitch)

        // --- 閃白 / 閃黑 ---
        flash(c, w, h, fx[Effect.FLASH_WHITE.ordinal], ::prog, Color.WHITE)
        flash(c, w, h, fx[Effect.FLASH_BLACK.ordinal], ::prog, Color.BLACK)
    }

    private inline fun flash(c: Canvas, w: Int, h: Int, i: Float, prog: (Float) -> Float, color: Int) {
        if (i <= 0f) return
        val q = prog(150f + 250f * i)
        if (q >= 1f) return
        val a = ((0.35f + 0.65f * i) * (1f - q) * (1f - q) * 255).toInt().coerceIn(0, 255)
        overlayPaint.color = color; overlayPaint.alpha = a
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), overlayPaint)
    }

    private fun computeFx(p: Project, info: BeatInfo) {
        fx.fill(0f)
        if (info.beforeStart) return
        var count = 0
        for (e in effects) if (e.transition && p.effect(e).enabled) enabledBuf[count++] = e.ordinal
        if (count == 0) return
        val mul = if (p.accentMode && !info.changeOnDownbeat) p.weakBeatStrength else 1f
        if (p.randomMode && count > 1) {
            var pick = (mix(info.slideNum) % count).toInt()
            val prevPick = (mix(info.slideNum - 1) % count).toInt()
            if (pick == prevPick) pick = (pick + 1 + (mix(info.slideNum xor 0x5bd1e995L) % (count - 1)).toInt()) % count
            val e = effects[enabledBuf[pick]]
            fx[e.ordinal] = p.effect(e).intensity * mul
        } else {
            for (k in 0 until count) {
                val e = effects[enabledBuf[k]]
                fx[e.ordinal] = p.effect(e).intensity * mul
            }
        }
    }

    private var kbS = 1f; private var kbX = 0f; private var kbY = 0f
    private fun kenBurns(p: Project, slideNum: Long, q: Float, w: Int, h: Int) {
        kbS = 1f; kbX = 0f; kbY = 0f
        val c = p.effect(Effect.KEN_BURNS)
        if (!c.enabled) return
        val z = 0.04f + 0.16f * c.intensity
        when ((mix(slideNum xor 0x1234567L) % 6).toInt()) {
            0 -> kbS = 1f + z * q
            1 -> kbS = 1f + z * (1f - q)
            2 -> { kbS = 1f + z; kbX = (q - 0.5f) * z * w }
            3 -> { kbS = 1f + z; kbX = (0.5f - q) * z * w }
            4 -> { kbS = 1f + z; kbY = (0.5f - q) * z * h }
            else -> { kbS = 1f + z * q; kbX = q * z * w * 0.25f; kbY = -q * z * h * 0.2f }
        }
    }

    private fun buildBands(slideNum: Long, since: Double, i: Float, q: Float, w: Int, h: Int): Boolean {
        val bucket = (since / 45.0).toLong()
        var r = mix(slideNum * 131 + bucket)
        fun rnd(): Float { r = mix(r); return ((r ushr 11) and 0xFFFFF).toFloat() / 0xFFFFF }
        if (bucket > 0 && rnd() < 0.2f) return false   // 偶爾空一格，產生閃爍感
        bandCount = 0
        var y = rnd() * h * 0.15f
        while (y < h && bandCount < 16) {
            val bh = h * (0.02f + rnd() * 0.10f)
            if (rnd() < 0.6f) {
                bands[bandCount * 3] = y
                bands[bandCount * 3 + 1] = min(h.toFloat(), y + bh)
                bands[bandCount * 3 + 2] = (rnd() * 2f - 1f) * w * (0.02f + 0.12f * i) * (1f - q)
                bandCount++
            }
            y += bh + rnd() * h * 0.12f
        }
        return bandCount > 0
    }

    private fun computeDst(b: Bitmap, w: Int, h: Int, fit: FitMode) {
        val sc = if (fit == FitMode.FILL) max(w.toFloat() / b.width, h.toFloat() / b.height)
        else min(w.toFloat() / b.width, h.toFloat() / b.height)
        val dw = b.width * sc; val dh = b.height * sc
        dst.set((w - dw) / 2f, (h - dh) / 2f, (w + dw) / 2f, (h + dh) / 2f)
    }

    private fun drawImage(
        c: Canvas, b: Bitmap, w: Int, h: Int, fit: FitMode,
        s: Float, tx: Float, ty: Float, alpha: Int, rgbOff: Float, glitch: Boolean,
    ) {
        computeDst(b, w, h, fit)
        val outer = c.save()
        if (alpha < 255) c.saveLayerAlpha(0f, 0f, w.toFloat(), h.toFloat(), alpha)
        c.translate(w / 2f + tx, h / 2f + ty)
        c.scale(s, s)
        c.translate(-w / 2f, -h / 2f)
        if (rgbOff != 0f) {
            c.saveLayer(null, null)
            c.drawRect(dst, blackPaint)
            for (ch in 0..2) pass(c, b, channelPaints[ch], (ch - 1) * rgbOff, (1 - ch) * rgbOff * 0.25f, glitch, w)
            c.restore()
        } else {
            pass(c, b, paint, 0f, 0f, glitch, w)
        }
        c.restoreToCount(outer)
    }

    /** 畫一次圖片；故障模式下切片之間不重疊，所以在 RGB 疊加模式也正確 */
    private fun pass(c: Canvas, b: Bitmap, pt: Paint, dx: Float, dy: Float, glitch: Boolean, w: Int) {
        if (!glitch) {
            tmp.set(dst); tmp.offset(dx, dy)
            c.drawBitmap(b, null, tmp, pt)
            return
        }
        val big = w * 4f
        c.save()
        for (k in 0 until bandCount) c.clipOutRect(-big, bands[k * 3], big, bands[k * 3 + 1])
        tmp.set(dst); tmp.offset(dx, dy)
        c.drawBitmap(b, null, tmp, pt)
        c.restore()
        for (k in 0 until bandCount) {
            c.save()
            c.clipRect(-big, bands[k * 3], big, bands[k * 3 + 1])
            tmp.set(dst); tmp.offset(dx + bands[k * 3 + 2], dy)
            c.drawBitmap(b, null, tmp, pt)
            c.restore()
        }
    }

    private fun drawLamp(c: Canvas, w: Int, info: BeatInfo, p: Project) {
        val r = 9f * density
        val cx = w - 22f * density; val cy = 22f * density
        if (!info.beforeStart) {
            val a = exp(-info.sinceBeatMs / 160.0).toFloat()
            lampPaint.color = if (info.beatInBar == 0) LAMP_DOWN else LAMP_BEAT
            lampPaint.alpha = (a * 255).toInt().coerceIn(0, 255)
            c.drawCircle(cx, cy, r * (1f + 0.25f * a), lampPaint)
        }
        c.drawCircle(cx, cy, r, lampRing)
    }

    companion object {
        val LAMP_DOWN = Color.rgb(255, 79, 139)
        val LAMP_BEAT = Color.rgb(139, 124, 255)

        private fun smooth(x: Float) = x * x * (3f - 2f * x)

        /** splitmix64：用換圖序號產生穩定的亂數，拖動進度條回來特效也一樣 */
        fun mix(v: Long): Long {
            var z = v + -0x61c8864680b583ebL
            z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
            z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
            return (z xor (z ushr 31)) and Long.MAX_VALUE
        }
    }
}
