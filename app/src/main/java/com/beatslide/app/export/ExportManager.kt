package com.beatslide.app.export

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import com.beatslide.app.data.Project
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** 匯出工作（獨立執行緒；離開編輯頁也會繼續跑） */
object ExportManager {
    data class State(
        val projectId: String,
        val running: Boolean,
        val progress: Float = 0f,
        val etaSec: Int? = null,
        val resultUri: Uri? = null,
        val resultName: String? = null,
        val resultInfo: String? = null,
        val error: String? = null,
    )

    private val _state = MutableStateFlow<State?>(null)
    val state: StateFlow<State?> = _state
    @Volatile private var cancelFlag = false

    fun start(context: Context, project: Project, s: ExportSettings) {
        if (_state.value?.running == true) return
        cancelFlag = false
        val app = context.applicationContext
        _state.value = State(project.id, running = true)
        thread(name = "BeatSlideExport") {
            val tmp = File(app.cacheDir, "export_tmp.mp4").apply { delete() }
            val t0 = SystemClock.elapsedRealtime()
            var lastP = 0f
            try {
                val r = VideoExporter.export(app, project, s, tmp, progress = { p ->
                    if (p - lastP >= 0.003f || p >= 1f) {
                        lastP = p
                        val el = (SystemClock.elapsedRealtime() - t0) / 1000.0
                        val eta = if (p > 0.12f) ((el / p) * (1 - p)).toInt() else null
                        _state.value = _state.value?.copy(progress = p, etaSec = eta)
                    }
                }, cancelled = { cancelFlag })
                val name = fileName(project.name)
                val uri = saveToGallery(app, tmp, name)
                val mb = tmp.length() / 1024.0 / 1024.0
                _state.value = State(
                    project.id, running = false, progress = 1f, resultUri = uri, resultName = name,
                    resultInfo = "${r.width}×${r.height} · ${s.fps} fps · %.1f MB".format(mb),
                )
            } catch (e: ExportCancelled) {
                _state.value = null
            } catch (e: Throwable) {
                _state.value = State(project.id, running = false, error = e.message ?: e.javaClass.simpleName)
            } finally {
                tmp.delete()
            }
        }
    }

    fun cancel() { cancelFlag = true }

    /** 關閉結果畫面 */
    fun clear() { if (_state.value?.running != true) _state.value = null }

    private fun fileName(projectName: String): String {
        val safe = projectName.replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_").take(40)
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return "BeatSlide_${safe}_$ts.mp4"
    }

    /** 存到「影片 / Movies/BeatSlide」，相簿看得到 */
    private fun saveToGallery(context: Context, src: File, name: String): Uri {
        val cr = context.contentResolver
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/BeatSlide")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri = cr.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
                ?: error("無法建立影片檔")
            try {
                cr.openOutputStream(uri)!!.use { out -> src.inputStream().use { it.copyTo(out) } }
                cr.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
            } catch (e: Throwable) {
                cr.delete(uri, null, null); throw e
            }
            return uri
        }
        // Android 8~9：寫到公開的 Movies 資料夾（需要儲存權限），再請系統掃描
        @Suppress("DEPRECATION")
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "BeatSlide").apply { mkdirs() }
        val dst = File(dir, name)
        src.copyTo(dst, overwrite = true)
        var result: Uri? = null
        val latch = CountDownLatch(1)
        MediaScannerConnection.scanFile(context, arrayOf(dst.absolutePath), arrayOf("video/mp4")) { _, uri ->
            result = uri; latch.countDown()
        }
        latch.await(5, TimeUnit.SECONDS)
        return result ?: Uri.fromFile(dst)
    }
}
