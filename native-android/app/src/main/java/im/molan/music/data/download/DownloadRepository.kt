package im.molan.music.data.download

import android.content.Context
import android.net.Uri
import android.os.Environment
import im.molan.music.model.DownloadEntry
import im.molan.music.model.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * 轻音自己的下载队列：完全由应用管理，最多三个音频任务同时下载。
 * 队列、URL 与状态均持久化；应用重启后会自动继续尚未完成的任务。
 */
class DownloadRepository(private val context: Context) {
    private data class Task(
        val id: Long,
        val title: String,
        val artist: String,
        val url: String,
        val fileName: String,
        val status: DownloadEntry.Status,
        val bytesDownloaded: Long = 0L,
        val totalBytes: Long = 0L,
    ) {
        fun toEntry() = DownloadEntry(id, title, artist, status, bytesDownloaded, totalBytes, fileName)
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queueLimit = Semaphore(3)
    private val stateLock = Any()
    private val nextId = AtomicLong(System.currentTimeMillis())
    private val storageDir = File(
        context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: context.filesDir,
        "轻音下载",
    ).apply { mkdirs() }
    private val stateFile = File(context.filesDir, "qingyin-internal-downloads.json")
    private val _tasks = MutableStateFlow(loadTasks())
    private val _taskEntries = MutableStateFlow(_tasks.value.map(Task::toEntry).sortedByDescending(DownloadEntry::id))
    val taskEntries: StateFlow<List<DownloadEntry>> = _taskEntries.asStateFlow()

    init {
        _tasks.value.filter { it.status == DownloadEntry.Status.QUEUED || it.status == DownloadEntry.Status.DOWNLOADING }
            .forEach { task ->
                updateTask(task.copy(status = DownloadEntry.Status.QUEUED, bytesDownloaded = 0L))
                schedule(task.id)
            }
    }

    fun enqueue(url: String, title: String, artist: String, fileName: String): Long {
        require(url.startsWith("https://") || url.startsWith("http://")) { "下载地址无效" }
        val id = nextId.incrementAndGet()
        val safeName = fileName
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .take(120)
            .ifBlank { "轻音下载" }
        val task = Task(
            id = id,
            title = title.ifBlank { "轻音下载" },
            artist = artist,
            url = url,
            fileName = "$id-$safeName",
            status = DownloadEntry.Status.QUEUED,
        )
        addTask(task)
        schedule(id)
        return id
    }

    fun retry(id: Long) {
        val task = _tasks.value.firstOrNull { it.id == id } ?: return
        if (task.status == DownloadEntry.Status.COMPLETED) return
        File(storageDir, "${task.fileName}.part").delete()
        updateTask(task.copy(status = DownloadEntry.Status.QUEUED, bytesDownloaded = 0L, totalBytes = 0L))
        schedule(id)
    }

    suspend fun entries(): List<DownloadEntry> = taskEntries.value

    suspend fun downloadedTracks(): List<Track> = _tasks.value
        .asSequence()
        .filter { it.status == DownloadEntry.Status.COMPLETED }
        .map { task -> task to File(storageDir, task.fileName) }
        .filter { (_, file) -> file.isFile && file.length() > 0L }
        .map { (task, file) ->
            Track(
                id = "download:${task.id}",
                title = task.title,
                artist = task.artist.substringBefore(" · ").ifBlank { "未知歌手" },
                uri = Uri.fromFile(file),
                source = Track.Source.DOWNLOADED,
                localFileName = task.fileName,
            )
        }
        .sortedBy { it.title.lowercase() }
        .toList()

    private fun schedule(id: Long) {
        scope.launch { queueLimit.withPermit { download(id) } }
    }

    private fun download(id: Long) {
        val initial = _tasks.value.firstOrNull { it.id == id } ?: return
        if (initial.status == DownloadEntry.Status.COMPLETED) return
        val partFile = File(storageDir, "${initial.fileName}.part")
        val finalFile = File(storageDir, initial.fileName)
        runCatching {
            updateTask(initial.copy(status = DownloadEntry.Status.DOWNLOADING, bytesDownloaded = 0L, totalBytes = 0L))
            partFile.delete()
            client.newCall(Request.Builder().url(initial.url).get().build()).execute().use { response ->
                require(response.isSuccessful) { "下载 HTTP ${response.code}" }
                val body = requireNotNull(response.body) { "下载响应为空" }
                val total = body.contentLength().coerceAtLeast(0L)
                var downloaded = 0L
                var lastReported = 0L
                body.byteStream().use { input ->
                    FileOutputStream(partFile).use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            downloaded += count
                            if (downloaded - lastReported >= 256 * 1024L || (total > 0L && downloaded == total)) {
                                lastReported = downloaded
                                updateTask(id) { it.copy(status = DownloadEntry.Status.DOWNLOADING, bytesDownloaded = downloaded, totalBytes = total) }
                            }
                        }
                        output.fd.sync()
                    }
                }
                require(downloaded > 0L) { "下载文件为空" }
                if (finalFile.exists()) finalFile.delete()
                require(partFile.renameTo(finalFile)) { "无法写入下载文件" }
                updateTask(id) { it.copy(status = DownloadEntry.Status.COMPLETED, bytesDownloaded = downloaded, totalBytes = if (total > 0L) total else downloaded) }
            }
        }.onFailure {
            partFile.delete()
            updateTask(id) { it.copy(status = DownloadEntry.Status.FAILED) }
        }
    }

    private fun addTask(task: Task) = synchronized(stateLock) {
        _tasks.value = (_tasks.value.filterNot { it.id == task.id } + task)
        persistLocked()
    }

    private fun updateTask(task: Task) = synchronized(stateLock) {
        _tasks.value = _tasks.value.map { if (it.id == task.id) task else it }
        persistLocked()
    }

    private fun updateTask(id: Long, transform: (Task) -> Task) = synchronized(stateLock) {
        _tasks.value = _tasks.value.map { if (it.id == id) transform(it) else it }
        persistLocked()
    }

    private fun persistLocked() {
        stateFile.writeText(JSONArray().apply {
            _tasks.value.forEach { task ->
                put(JSONObject()
                    .put("id", task.id)
                    .put("title", task.title)
                    .put("artist", task.artist)
                    .put("url", task.url)
                    .put("fileName", task.fileName)
                    .put("status", task.status.name)
                    .put("bytes", task.bytesDownloaded)
                    .put("total", task.totalBytes))
            }
        }.toString())
        _taskEntries.value = _tasks.value.map(Task::toEntry).sortedByDescending(DownloadEntry::id)
    }

    private fun loadTasks(): List<Task> = runCatching {
        val rows = JSONArray(stateFile.readText())
        buildList {
            for (index in 0 until rows.length()) {
                val row = rows.optJSONObject(index) ?: continue
                val url = row.optString("url")
                if (url.isBlank()) continue
                add(Task(
                    id = row.optLong("id"),
                    title = row.optString("title").ifBlank { "轻音下载" },
                    artist = row.optString("artist"),
                    url = url,
                    fileName = row.optString("fileName").ifBlank { "轻音下载" },
                    status = runCatching { DownloadEntry.Status.valueOf(row.optString("status")) }.getOrDefault(DownloadEntry.Status.FAILED),
                    bytesDownloaded = row.optLong("bytes"),
                    totalBytes = row.optLong("total"),
                ))
            }
        }
    }.getOrDefault(emptyList())
}
