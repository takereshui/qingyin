package im.molan.music.data.download

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import im.molan.music.data.settings.SettingsRepository
import im.molan.music.model.DownloadEntry
import im.molan.music.model.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * 轻音自己的下载队列：完全由应用管理，最多三个音频任务同时下载。
 * 队列、URL 与状态均持久化；应用重启后会自动继续尚未完成的任务。
 */
class DownloadRepository(private val context: Context, private val settingsRepository: SettingsRepository) {
    data class EnqueueResult(val id: Long, val added: Boolean)

    /** 完成后的落盘结果：优先 MediaStore Uri，其次真实文件路径。 */
    private data class PublishResult(val mediaUri: String? = null, val finalPath: String? = null)

    private data class Task(
        val id: Long,
        val title: String,
        val artist: String,
        val url: String,
        val fileName: String,
        val status: DownloadEntry.Status,
        val referer: String? = null,
        val bytesDownloaded: Long = 0L,
        val totalBytes: Long = 0L,
        val errorMessage: String? = null,
        /** 下载完成后的公开可播放地址：Q+ 的 MediaStore Uri 或 SAF 自定义目录 Uri。 */
        val mediaUri: String? = null,
        /** 下载完成后的真实文件绝对路径（pre-Q 公共目录或私有兜底）。 */
        val finalPath: String? = null,
    ) {
        fun toEntry() = DownloadEntry(id, title, artist, status, bytesDownloaded, totalBytes, fileName, errorMessage)
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queueLimit = Semaphore(3)
    private val stateLock = Any()
    private val nextId = AtomicLong(System.currentTimeMillis())
    /** 前台服务启停只按“任务活跃/空闲”边界触发，避免下载进度刷新时反复拉起。 */
    private var serviceActive = false
    /** 下载过程工作目录（app 私有，.part 断点续传不受公共目录权限影响）。 */
    private val workDir = File(context.filesDir, "轻音下载-work").apply { mkdirs() }
    /** 旧版本把完成文件写在这里；仅用于回溯旧任务的可播放地址。 */
    private val legacyDir = File(
        context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: context.filesDir,
        "轻音下载",
    )
    private val stateFile = File(context.filesDir, "qingyin-internal-downloads.json")
    private val _tasks = MutableStateFlow(loadTasks())
    private val _taskEntries = MutableStateFlow(_tasks.value.map(Task::toEntry).sortedByDescending(DownloadEntry::id))
    val taskEntries: StateFlow<List<DownloadEntry>> = _taskEntries.asStateFlow()
    /** 用户选择的 SAF 下载目录；由设置流实时更新。 */
    @Volatile
    private var customDownloadFolder: String? = null

    init {
        scope.launch {
            settingsRepository.settings.collect { customDownloadFolder = it.downloadFolderUri.ifBlank { null } }
        }
        // 应用重启时自动继续未完成的任务；从 .part 文件恢复既有进度实现断点续传。
        _tasks.value.filter { it.status == DownloadEntry.Status.QUEUED || it.status == DownloadEntry.Status.DOWNLOADING }
            .forEach { task ->
                val partBytes = File(workDir, "${task.fileName}.part").length().coerceAtLeast(0L)
                updateTask(task.copy(status = DownloadEntry.Status.QUEUED, bytesDownloaded = partBytes, errorMessage = null))
                schedule(task.id)
            }
    }

    fun enqueue(url: String, title: String, artist: String, fileName: String, referer: String? = null): Long =
        enqueueIfAbsent(url, title, artist, fileName, referer).id

    /** 相同标题和艺人的下载任务（包括已完成/排队/失败）只保留一条，避免歌单批量下载重复入队。 */
    fun enqueueIfAbsent(url: String, title: String, artist: String, fileName: String, referer: String? = null): EnqueueResult {
        require(url.startsWith("https://") || url.startsWith("http://")) { "下载地址无效" }
        val cleanTitle = title.ifBlank { "轻音下载" }
        val identity = downloadIdentity(cleanTitle, artist)
        var created: Task? = null
        val result = synchronized(stateLock) {
            val existing = _tasks.value.firstOrNull { downloadIdentity(it.title, it.artist) == identity }
            if (existing != null) {
                if (existing.status == DownloadEntry.Status.FAILED || existing.status == DownloadEntry.Status.MISSING) {
                    // 任务之前失败：保留已下载的 .part 断点，重新排队以便续传。
                    val updated = existing.copy(status = DownloadEntry.Status.QUEUED, errorMessage = null)
                    _tasks.value = _tasks.value.map { if (it.id == existing.id) updated else it }
                    persistLocked()
                    EnqueueResult(existing.id, added = true)
                } else {
                    EnqueueResult(existing.id, added = false)
                }
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
                    referer = referer,
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
        File(workDir, "${task.fileName}.part").delete()
        updateTask(task.copy(status = DownloadEntry.Status.QUEUED, bytesDownloaded = 0L, totalBytes = 0L))
        schedule(id)
    }

    /**
     * 在线试听缓存已完整命中时直接“另存”为下载：从缓存读取字节写入下载目录并发布，
     * 不重新请求网络，秒完成。readBytes 由调用方提供缓存字节流。
     */
    fun enqueueFromCached(
        url: String,
        title: String,
        artist: String,
        fileName: String,
        referer: String?,
        readBytes: () -> InputStream?,
    ): EnqueueResult {
        require(url.startsWith("https://") || url.startsWith("http://")) { "下载地址无效" }
        val cleanTitle = title.ifBlank { "轻音下载" }
        val identity = downloadIdentity(cleanTitle, artist)
        val created = synchronized(stateLock) {
            val existing = _tasks.value.firstOrNull { downloadIdentity(it.title, it.artist) == identity }
            if (existing != null) {
                null to EnqueueResult(existing.id, added = false)
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
                    status = DownloadEntry.Status.DOWNLOADING,
                    referer = referer,
                )
                _tasks.value = _tasks.value + task
                persistLocked()
                task to EnqueueResult(id, added = true)
            }
        }
        created.first?.let { task -> scope.launch { saveFromCached(task, readBytes) } }
        return created.second
    }

    private suspend fun saveFromCached(task: Task, readBytes: () -> InputStream?) {
        val finalFile = File(workDir, task.fileName)
        runCatching {
            val input = readBytes() ?: error("在线缓存未命中，请稍后重试")
            input.use { src -> finalFile.outputStream().use { dst -> src.copyTo(dst) } }
            require(finalFile.length() > 0L) { "缓存内容为空" }
            val published = publishCompleted(finalFile, task.fileName.substringAfter('-'))
            updateTask(
                task.copy(
                    status = DownloadEntry.Status.COMPLETED,
                    bytesDownloaded = finalFile.length(),
                    totalBytes = finalFile.length(),
                    errorMessage = null,
                    mediaUri = published?.mediaUri,
                    finalPath = published?.finalPath,
                ),
            )
        }.onFailure { error ->
            updateTask(task.copy(status = DownloadEntry.Status.FAILED, errorMessage = error.message ?: "缓存另存失败"))
        }
    }

    /** 仍有排队或下载中的任务时返回 true，用于前台服务保活判断。 */
    fun hasActiveTasks(): Boolean = _tasks.value.any {
        it.status == DownloadEntry.Status.QUEUED || it.status == DownloadEntry.Status.DOWNLOADING
    }

    /** 正在下载的任务数量，供通知文案展示。 */
    fun activeDownloadCount(): Int = _tasks.value.count { it.status == DownloadEntry.Status.DOWNLOADING }

    suspend fun entries(): List<DownloadEntry> = taskEntries.value

    suspend fun downloadedTracks(): List<Track> = withContext(Dispatchers.IO) {
        _tasks.value
            .asSequence()
            .filter { it.status == DownloadEntry.Status.COMPLETED }
            .mapNotNull { task -> task.toDownloadedTrack() }
            .sortedBy { it.title.lowercase() }
            .toList()
    }

    /** 已下载曲目按优先级取地址：MediaStore/SAF Uri → 完成路径 → 当前工作目录 → 旧版私有目录。 */
    private fun Task.toDownloadedTrack(): Track? {
        val uri = mediaUri?.takeIf(String::isNotBlank)?.let(Uri::parse)
        if (uri != null) {
            return Track(
                id = "download:$id",
                title = title,
                artist = artist.substringBefore(" · ").ifBlank { "未知歌手" },
                uri = uri,
                source = Track.Source.DOWNLOADED,
                localFileName = fileName,
            )
        }
        val file = finalPath?.let(::File)?.takeIf { it.isFile && it.length() > 0L }
            ?: File(workDir, fileName).takeIf { it.isFile && it.length() > 0L }
            ?: File(legacyDir, fileName).takeIf { it.isFile && it.length() > 0L }
            ?: return null
        return Track(
            id = "download:$id",
            title = title,
            artist = artist.substringBefore(" · ").ifBlank { "未知歌手" },
            uri = Uri.fromFile(file),
            source = Track.Source.DOWNLOADED,
            localFileName = fileName,
        )
    }

    private fun schedule(id: Long) {
        scope.launch { queueLimit.withPermit { download(id) } }
    }

    private fun download(id: Long) {
        val initial = _tasks.value.firstOrNull { it.id == id } ?: return
        if (initial.status == DownloadEntry.Status.COMPLETED) return
        val partFile = File(workDir, "${initial.fileName}.part")
        val finalFile = File(workDir, initial.fileName)
        runCatching {
            // 断点续传：已有 .part 时从既有字节继续，同时写回任务进度。
            val resumedBytes = if (partFile.isFile) partFile.length().coerceAtLeast(0L) else 0L
            updateTask(initial.copy(
                status = DownloadEntry.Status.DOWNLOADING,
                bytesDownloaded = resumedBytes,
                totalBytes = initial.totalBytes,
            ))
            val requestBuilder = Request.Builder()
                .url(initial.url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .apply {
                    if (!initial.referer.isNullOrBlank()) {
                        header("Referer", initial.referer)
                    }
                }
                .get()
            if (resumedBytes > 0L) requestBuilder.header("Range", "bytes=$resumedBytes-")
            val request = requestBuilder.build()
            client.newCall(request).execute().use { response ->
                require(response.isSuccessful || response.code == 206) { "下载失败 (HTTP ${response.code})" }
                val body = requireNotNull(response.body) { "下载响应为空" }
                val serverTotal = body.contentLength()
                // 服务器没有返回 206 时视为不支持断点续传：丢弃旧 .part 从头下载，避免文件损坏。
                val canResume = response.code == 206 && resumedBytes > 0L
                if (!canResume && resumedBytes > 0L) partFile.delete()
                val total = if (canResume) {
                    (resumedBytes + serverTotal).coerceAtLeast(resumedBytes)
                } else {
                    serverTotal.coerceAtLeast(resumedBytes + serverTotal)
                }
                var downloaded = if (canResume) resumedBytes else 0L
                var lastReported = if (canResume) resumedBytes else 0L
                body.byteStream().use { input ->
                    val output = FileOutputStream(partFile, /* append = */ canResume)
                    java.io.BufferedOutputStream(output).use { buffered ->
                        val buffer = ByteArray(64 * 1024) // 增大缓冲区至 64KB
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            buffered.write(buffer, 0, count)
                            downloaded += count
                            if (downloaded - lastReported >= 512 * 1024L || (total > 0L && downloaded == total)) {
                                lastReported = downloaded
                                updateTask(id) { it.copy(status = DownloadEntry.Status.DOWNLOADING, bytesDownloaded = downloaded, totalBytes = total) }
                            }
                        }
                        buffered.flush()
                    }
                }
                require(downloaded > 0L) { "下载文件内容为空" }
                if (finalFile.exists()) finalFile.delete()
                require(partFile.renameTo(finalFile)) { "文件重命名失败，请检查存储空间" }
                // 完成后发布到公共目录（MediaStore / SAF / 公共 Music），让系统媒体库与外部播放器可见。
                val published = publishCompleted(finalFile, initial.fileName.substringAfter('-'))
                updateTask(id) {
                    it.copy(
                        status = DownloadEntry.Status.COMPLETED,
                        bytesDownloaded = downloaded,
                        totalBytes = if (total > 0L) total else downloaded,
                        errorMessage = null,
                        mediaUri = published?.mediaUri,
                        finalPath = published?.finalPath,
                    )
                }
            }
        }.onFailure { error ->
            // 保留 .part 以支持续传，只有明确失败才保留进度文件；完成前不删除。
            updateTask(id) { it.copy(status = DownloadEntry.Status.FAILED, errorMessage = error.message ?: "未知下载错误") }
        }
    }

    private fun downloadIdentity(title: String, artist: String): String =
        "${title.trim().lowercase()}|${artist.substringBefore(" · ").trim().lowercase()}"

    /**
     * 把已完成的音频文件发布到用户可见目录：
     * 1) 用户选了 SAF 下载目录 → 写入该目录；
     * 2) Android 10+ → 注册进 MediaStore（公共 Music/轻音下载，系统自动索引）；
     * 3) 更早版本 → 直接写公共 Music 目录并通知媒体扫描（无权限时保留在私有工作目录兜底）。
     * 返回 PublishResult 供任务持久化；任何失败都不影响已完成文件继续可播。
     */
    private fun publishCompleted(file: File, displayName: String): PublishResult? {
        val safeName = displayName.ifBlank { file.name }.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(120)
        val mime = mimeType(safeName)
        customDownloadFolder?.takeIf(String::isNotBlank)?.let { treeUri ->
            return runCatching {
                val root = DocumentFile.fromTreeUri(context, Uri.parse(treeUri)) ?: return@runCatching null
                val target = root.createFile(mime, safeName)
                val stream = target.openOutputStream("w") ?: return@runCatching null
                stream.use { out -> file.inputStream().use { it.copyTo(out) } }
                file.delete()
                PublishResult(mediaUri = target.uri.toString())
            }.getOrNull()
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return runCatching {
                val resolver = context.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.Audio.Media.DISPLAY_NAME, safeName)
                    put(MediaStore.Audio.Media.MIME_TYPE, mime)
                    put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/轻音下载")
                    put(MediaStore.Audio.Media.IS_PENDING, 1)
                }
                val uri = resolver.insert(
                    MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                    values,
                ) ?: error("MediaStore 创建失败")
                val published = try {
                    resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                    val done = ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }
                    resolver.update(uri, done, null, null)
                    file.delete()
                    PublishResult(mediaUri = uri.toString())
                } catch (e: Exception) {
                    resolver.delete(uri, null, null)
                    throw e
                }
                published
            }.getOrNull()
        }
        // Android 10 以下：直接写公共 Music 目录，成功后通知媒体扫描。
        return runCatching {
            val publicDir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
                "轻音下载",
            ).apply { mkdirs() }
            val dest = File(publicDir, safeName)
            file.copyTo(dest, overwrite = true)
            MediaScannerConnection.scanFile(context, arrayOf(dest.absolutePath), arrayOf(mime), null)
            file.delete()
            PublishResult(finalPath = dest.absolutePath)
        }.getOrNull()
    }

    private fun mimeType(fileName: String): String = when (fileName.substringAfterLast('.', "").lowercase()) {
        "flac" -> "audio/flac"
        "m4a", "aac", "mp4" -> "audio/mp4"
        "ogg" -> "audio/ogg"
        "opus" -> "audio/opus"
        "wav" -> "audio/wav"
        else -> "audio/mpeg"
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
        runCatching {
            stateFile.writeText(JSONArray().apply {
                _tasks.value.forEach { task ->
                    put(JSONObject()
                        .put("id", task.id)
                        .put("title", task.title)
                        .put("artist", task.artist)
                        .put("url", task.url)
                        .put("fileName", task.fileName)
                        .put("status", task.status.name)
                        .put("referer", task.referer.orEmpty())
                        .put("bytes", task.bytesDownloaded)
                        .put("total", task.totalBytes)
                        .put("error", task.errorMessage.orEmpty())
                        .put("mediaUri", task.mediaUri.orEmpty())
                        .put("finalPath", task.finalPath.orEmpty()))
                }
            }.toString())
        }
        _taskEntries.value = _tasks.value.map(Task::toEntry).sortedByDescending(DownloadEntry::id)
        syncForegroundService()
    }

    /** 有活跃任务就拉起前台服务保活；任务全部结束就撤掉。只在边界触发。 */
    private fun syncForegroundService() = synchronized(stateLock) {
        val hasActive = hasActiveTasks()
        if (hasActive && !serviceActive) {
            serviceActive = true
            runCatching { DownloadService.start(context.applicationContext) }
                .onFailure { serviceActive = false } // 后台启动受限等场景下保活失败不致命，下载照常进行
        } else if (!hasActive && serviceActive) {
            serviceActive = false
            runCatching { DownloadService.stop(context.applicationContext) }
        }
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
                    referer = row.optString("referer").takeIf(String::isNotBlank),
                    bytesDownloaded = row.optLong("bytes"),
                    totalBytes = row.optLong("total"),
                    errorMessage = row.optString("error").takeIf(String::isNotBlank),
                    mediaUri = row.optString("mediaUri").takeIf(String::isNotBlank),
                    finalPath = row.optString("finalPath").takeIf(String::isNotBlank),
                ))
            }
        }
    }.getOrDefault(emptyList())
}
