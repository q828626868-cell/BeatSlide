package com.beatslide.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt

/** 把使用者選的音樂/圖片複製進專案資料夾（圖片會縮到合理尺寸並產生縮圖） */
object MediaImporter {
    private const val MAX_IMAGE_SIDE = 2560
    private const val THUMB_SIDE = 360

    fun thumbName(imageName: String) = "thumb_$imageName"

    data class MusicInfo(val fileName: String, val displayName: String, val durationMs: Long)

    fun importMusic(context: Context, projectId: String, uri: Uri): MusicInfo {
        val cr = context.contentResolver
        var display = "音樂"
        cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0)?.let { display = it }
        }
        val ext = display.substringAfterLast('.', "").lowercase().takeIf { it.length in 2..5 } ?: "audio"
        val name = "music_${UUID.randomUUID().toString().take(8)}.$ext"
        ProjectStore.dir(projectId).mkdirs()
        val dst = ProjectStore.file(projectId, name)
        cr.openInputStream(uri)?.use { input -> FileOutputStream(dst).use { input.copyTo(it) } }
            ?: error("無法開啟檔案")
        val duration = runCatching {
            MediaMetadataRetriever().run {
                try {
                    setDataSource(dst.absolutePath)
                    extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L
                } finally { release() }
            }
        }.getOrDefault(0L)
        if (duration <= 0) {
            dst.delete()
            error("無法讀取這個音樂檔，請換一個格式（mp3 / m4a / wav）")
        }
        return MusicInfo(name, display.substringBeforeLast('.'), duration)
    }

    /** 回傳存好的檔名；失敗回傳 null */
    fun importImage(context: Context, projectId: String, uri: Uri): String? = runCatching {
        val bmp = decodeScaled(context, uri, MAX_IMAGE_SIDE) ?: return null
        val name = "img_${UUID.randomUUID().toString().take(12)}.jpg"
        ProjectStore.dir(projectId).mkdirs()
        FileOutputStream(ProjectStore.file(projectId, name)).use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        val ts = THUMB_SIDE.toFloat() / max(bmp.width, bmp.height)
        val thumb = if (ts < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * ts).roundToInt().coerceAtLeast(1),
            (bmp.height * ts).roundToInt().coerceAtLeast(1), true) else bmp
        FileOutputStream(ProjectStore.file(projectId, thumbName(name))).use { thumb.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        if (thumb !== bmp) thumb.recycle()
        bmp.recycle()
        name
    }.getOrNull()

    private fun decodeScaled(context: Context, uri: Uri, maxSide: Int): Bitmap? {
        val cr = context.contentResolver
        if (Build.VERSION.SDK_INT >= 28) {
            val src = ImageDecoder.createSource(cr, uri)
            return ImageDecoder.decodeBitmap(src) { dec, info, _ ->
                dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val w = info.size.width; val h = info.size.height
                val s = maxSide.toFloat() / max(w, h)
                if (s < 1f) dec.setTargetSize((w * s).roundToInt().coerceAtLeast(1), (h * s).roundToInt().coerceAtLeast(1))
            }
        }
        // Android 8.x：BitmapFactory + EXIF 旋轉
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        var bmp = cr.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        val rotation = cr.openInputStream(uri)?.use {
            when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } ?: 0f
        val s = maxSide.toFloat() / max(bmp.width, bmp.height)
        if (rotation != 0f || s < 1f) {
            val m = Matrix().apply { if (s < 1f) postScale(s, s); postRotate(rotation) }
            val r = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            if (r !== bmp) bmp.recycle()
            bmp = r
        }
        return bmp
    }

    fun projectFile(projectId: String, name: String): File = ProjectStore.file(projectId, name)
}
