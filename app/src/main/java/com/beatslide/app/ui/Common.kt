package com.beatslide.app.ui

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.beatslide.app.data.MediaImporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

val Accent = Color(0xFF8B7CFF)
val AccentPink = Color(0xFFFF4F8B)

private val scheme: ColorScheme = darkColorScheme(
    primary = Accent,
    onPrimary = Color(0xFF14102B),
    primaryContainer = Color(0xFF2E2754),
    onPrimaryContainer = Color(0xFFE3DEFF),
    secondary = Color(0xFF5FE3D0),
    onSecondary = Color(0xFF00201C),
    secondaryContainer = Color(0xFF2C2A3B),
    onSecondaryContainer = Color(0xFFE3DEFF),
    tertiary = AccentPink,
    background = Color(0xFF0E0E13),
    onBackground = Color(0xFFE6E4EE),
    surface = Color(0xFF0E0E13),
    onSurface = Color(0xFFE6E4EE),
    surfaceVariant = Color(0xFF24232D),
    onSurfaceVariant = Color(0xFFB2B0BE),
    surfaceContainerLowest = Color(0xFF0A0A0E),
    surfaceContainerLow = Color(0xFF15151C),
    surfaceContainer = Color(0xFF1A1A22),
    surfaceContainerHigh = Color(0xFF22222B),
    surfaceContainerHighest = Color(0xFF2B2A35),
    outline = Color(0xFF55535F),
    outlineVariant = Color(0xFF34333D),
    error = Color(0xFFFF6B6B),
)

@Composable
fun BeatSlideTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = Typography(), content = content)
}

@Composable
fun Ico(id: Int, desc: String? = null, modifier: Modifier = Modifier, tint: Color = MaterialTheme.colorScheme.onSurface, size: Dp = 24.dp) {
    Icon(painterResource(id), desc, modifier = modifier.size(size), tint = tint)
}

fun formatTime(ms: Double): String {
    val total = (ms / 1000).toLong().coerceAtLeast(0)
    return String.format(Locale.US, "%d:%02d", total / 60, total % 60)
}

fun formatBpm(b: Double): String = String.format(Locale.US, "%.1f", b)

fun formatOffset(ms: Long): String = String.format(Locale.US, "%.3f 秒", ms / 1000.0)

/** 縮圖快取（專案列表與素材格子用） */
object ThumbCache {
    private val cache = LruCache<String, ImageBitmap>(300)
    fun peek(f: File): ImageBitmap? = cache.get(f.absolutePath)
    fun load(img: File): ImageBitmap? {
        cache.get(img.absolutePath)?.let { return it }
        val t = File(img.parentFile, MediaImporter.thumbName(img.name))
        val bmp = if (t.exists()) BitmapFactory.decodeFile(t.absolutePath)
        else BitmapFactory.decodeFile(img.absolutePath, BitmapFactory.Options().apply { inSampleSize = 8 })
        val ib = bmp?.asImageBitmap() ?: return null
        cache.put(img.absolutePath, ib)
        return ib
    }
}

@Composable
fun Thumb(file: File, modifier: Modifier = Modifier) {
    val bmp by produceState(ThumbCache.peek(file), file) {
        if (value == null) value = withContext(Dispatchers.IO) { ThumbCache.load(file) }
    }
    val b = bmp
    if (b != null) Image(b, null, modifier = modifier, contentScale = ContentScale.Crop)
    else Box(modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh))
}
