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
    data class EnqueueResult(val id: Long, val added: Boolean)

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
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
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

    fun enqueue(url: String, title: String, artist: String, fileName: String): Long =
        enqueueIfAbsent(url, title, artist, fileName).id

    /** 相同标题和艺人的下载任务（包括已完成/排队/失败）只保留一条，避免歌单批量下载重复入队。 */
    fun enqueueIfAbsent(url: String, title: String, artist: String, fileName: String): EnqueueResult {
        require(url.startsWith("https://") || url.startsWith("http://")) { "下载地址无效" }
        val cleanTitle = title.ifBlank { "轻音下载" }
        val identity = downloadIdentity(cleanTitle, artist)
        var created: Task? = null
        val result = synchronized(stateLock) {
            val existing = _tasks.value.firstOrNull { downloadIdentity(it.title, it.artist) == identity }
            if (existing != null) {
                EnqueueResult(existing.id, added = false)
            } else {
                val id = nextId.incrementAndGet()
                val safeName = fileName
                    .replace(Regex("[\\\\/:*?\"<>|]"), "_")
                    .take(120)
                    .ifBlank { "轻音下载" }
                val task = Task(
                    id = id,
                    title = cleanTitle,
                    artist = artist,
                    url = url,
                    fileName = "$id-$safeName",
                    status = DownloadEntry.Status.QUEUED,
                )
                _tasks.value = _tasks.value + task
                persistLocked()
                created = task
                EnqueueResult(id, added = true)
            }
        }
        created?.let { schedule(it.id) }
        return result
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
            val request = Request.Builder()
                .url(initial.url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                require(response.isSuccessful) { "下载失败 (HTTP ${response.code})" }
                val body = requireNotNull(response.body) { "下载响应为空" }
                val total = body.contentLength().let { if (it <= 0L) 0L else it }
                var downloaded = 0L
                var lastReported = 0L
                body.byteStream().use { input ->
                    java.io.BufferedOutputStream(FileOutputStream(partFile)).use { output ->
                        val buffer = ByteArray(64 * 1024) // 增大缓冲区至 64KB
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            downloaded += count
                            if (downloaded - lastReported >= 512 * 1024L || (total > 0L && downloaded == total)) {
                                lastReported = downloaded
                                updateTask(id) { it.copy(status = DownloadEntry.Status.DOWNLOADING, bytesDownloaded = downloaded, totalBytes = total) }
                            }
                        }
                        output.flush()
                    }
                }
                require(downloaded > 0L) { "下载文件内容为空" }
                if (finalFile.exists()) finalFile.delete()
                require(partFile.renameTo(finalFile)) { "文件重命名失败，请检查存储空间" }
                updateTask(id) { it.copy(status = DownloadEntry.Status.COMPLETED, bytesDownloaded = downloaded, totalBytes = if (total > 0L) total else downloaded) }
            }
        }.onFailure { error ->
            partFile.delete()
            updateTask(id) { it.copy(status = DownloadEntry.Status.FAILED) }
        }
    }

    private fun downloadIdentity(title: String, artist: String): String =
        "${title.trim().lowercase()}|${artist.substringBefore(" · ").trim().lowercase()}"

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
