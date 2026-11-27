package com.beatslide.app.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * 專案資料存在 App 私有目錄：files/projects/<id>/project.json，
 * 匯入的音樂與圖片也複製到同一個資料夾。
 */
object ProjectStore {
    private lateinit var root: File
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pendingSaves = HashMap<String, Job>()
    private val lock = Any()

    private val _projects = MutableStateFlow<List<Project>>(emptyList())
    val projects: StateFlow<List<Project>> = _projects

    fun init(context: Context) {
        if (::root.isInitialized) return
        root = File(context.filesDir, "projects").apply { mkdirs() }
        val loaded = root.listFiles()?.mapNotNull { dir ->
            val f = File(dir, "project.json")
            if (!f.exists()) return@mapNotNull null
            runCatching { Project.fromJson(JSONObject(f.readText())) }.getOrNull()
        } ?: emptyList()
        _projects.value = loaded.sortedByDescending { it.updatedAt }
    }

    /** 注意：不在這裡 mkdirs（每格畫面都會呼叫，避免多餘的檔案系統操作） */
    fun dir(id: String): File = File(root, id)
    fun file(id: String, name: String): File = File(dir(id), name)

    fun get(id: String): Project? = _projects.value.firstOrNull { it.id == id }

    fun create(name: String): Project {
        val now = System.currentTimeMillis()
        val p = Project(id = UUID.randomUUID().toString(), name = name, createdAt = now, updatedAt = now)
        _projects.update { listOf(p) + it }
        writeNow(p)
        return p
    }

    fun rename(id: String, name: String) = update(id) { it.copy(name = name) }

    fun delete(id: String) {
        synchronized(lock) { pendingSaves.remove(id)?.cancel() }
        _projects.update { list -> list.filterNot { it.id == id } }
        scope.launch { synchronized(this@ProjectStore) { File(root, id).deleteRecursively() } }
    }

    /** 修改專案並排程存檔（300ms 內的多次修改合併成一次寫入） */
    fun update(id: String, transform: (Project) -> Project) {
        var updated: Project? = null
        _projects.update { list ->
            list.map {
                if (it.id == id) transform(it).copy(updatedAt = System.currentTimeMillis()).also { n -> updated = n } else it
            }
        }
        val p = updated ?: return
        synchronized(lock) {
            pendingSaves.remove(id)?.cancel()
            pendingSaves[id] = scope.launch {
                delay(300)
                writeNow(p)
                synchronized(lock) { pendingSaves.remove(id) }
            }
        }
    }

    /** App 進背景時呼叫，立刻寫出所有尚未存檔的修改 */
    fun flush() {
        val ids = synchronized(lock) {
            val k = pendingSaves.keys.toList()
            pendingSaves.values.forEach { it.cancel() }
            pendingSaves.clear()
            k
        }
        ids.forEach { id -> get(id)?.let { writeNow(it) } }
    }

    @Synchronized
    private fun writeNow(p: Project) {
        if (get(p.id) == null) return   // 已被刪除的專案不要再寫回去
        val d = dir(p.id).apply { mkdirs() }
        val tmp = File(d, "project.json.tmp")
        tmp.writeText(p.toJson().toString(2))
        val dst = File(d, "project.json")
        if (!tmp.renameTo(dst)) {
            dst.delete(); tmp.renameTo(dst)
        }
    }

    /** 刪除專案資料夾中已不再被引用的媒體檔 */
    fun deleteFiles(id: String, names: Collection<String>) {
        scope.launch {
            names.forEach { n ->
                File(dir(id), n).delete()
                File(dir(id), MediaImporter.thumbName(n)).delete()
            }
        }
    }
}
