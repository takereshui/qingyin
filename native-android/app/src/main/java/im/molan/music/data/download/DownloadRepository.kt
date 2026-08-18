package im.molan.music.data.download

import android.content.ContentValues
import android.content.Context
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.images.ArtworkFactory
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

    /** 下载字节完成后依据真实文件签名确认的容器与元数据处理结果。 */
    private data class PreparedAudio(
        val file: File,
        val task: Task,
        val containerFormat: String,
        val notice: String,
    )

    private data class Task(
        val id: Long,
        val title: String,
        val artist: String,
        /** 解析线上曲目时得到的原始专辑、封面和时长，用于下载后完整还原展示信息。 */
        val album: String = "",
        val artworkUri: String? = null,
        val durationMs: Long = 0L,
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
        /** 下载完成后由文件签名确认的实际容器格式。 */
        val containerFormat: String = "",
    ) {
        fun toEntry() = DownloadEntry(id, title, artist, status, bytesDownloaded, totalBytes, fileName, errorMessage, containerFormat)
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queueLimit = Semaphore(3)
    private val stateLock = Any()
    private val statePersistencePolicy = DownloadStatePersistencePolicy()
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
    private val _tasks = MutableStateFlow(migrateLegacyReaderFailures(loadTasks()))
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
    fun enqueueIfAbsent(
        url: String,
        title: String,
        artist: String,
        fileName: String,
        referer: String? = null,
        album: String = "",
        artworkUri: String? = null,
        durationMs: Long = 0L,
    ): EnqueueResult {
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
                    album = album,
                    artworkUri = artworkUri,
                    durationMs = durationMs,
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
        // 旧版在标签读取失败后可能留下已下载的最终文件；重试必须清理它，避免复用错误后缀。
        File(workDir, task.fileName).delete()
        updateTask(task.copy(status = DownloadEntry.Status.QUEUED, bytesDownloaded = 0L, totalBytes = 0L, errorMessage = null, containerFormat = ""))
        schedule(id)
    }

    /**
     * 删除轻音内置下载任务记录。对排队、下载中和失败任务会清理私有临时文件；
     * 已完成任务只删除轻音的记录，不删除用户已写入 MediaStore、SAF 或公共 Music 目录的音频文件。
     */
    fun removeRecord(id: Long): Boolean {
        val removed = synchronized(stateLock) {
            val task = _tasks.value.firstOrNull { it.id == id } ?: return@synchronized null
            _tasks.value = _tasks.value.filterNot { it.id == id }
            persistLocked()
            task
        } ?: return false
        if (removed.status != DownloadEntry.Status.COMPLETED) {
            File(workDir, "${removed.fileName}.part").delete()
            File(workDir, removed.fileName).delete()
        }
        return true
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
        album: String = "",
        artworkUri: String? = null,
        durationMs: Long = 0L,
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
                    album = album,
                    artworkUri = artworkUri,
                    durationMs = durationMs,
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
            if (_tasks.value.none { it.id == task.id }) {
                finalFile.delete()
                return@runCatching
            }
            val completedBytes = finalFile.length()
            val prepared = prepareDownloadedAudio(finalFile, task)
            val published = publishCompleted(prepared.file, prepared.task)
            updateTask(
                prepared.task.copy(
                    status = DownloadEntry.Status.COMPLETED,
                    bytesDownloaded = completedBytes,
                    totalBytes = completedBytes,
                    errorMessage = prepared.notice,
                    mediaUri = published?.mediaUri,
                    finalPath = published?.finalPath,
                    containerFormat = prepared.containerFormat,
                ),
            )
        }.onFailure { error ->
            val message = error.message?.takeIf(String::isNotBlank) ?: "缓存另存失败：${error.javaClass.simpleName}"
            updateTask(task.copy(status = DownloadEntry.Status.FAILED, errorMessage = message))
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
        val playableUri = mediaUri?.takeIf(String::isNotBlank)?.let(Uri::parse)
            ?: (finalPath?.let(::File)?.takeIf { it.isFile && it.length() > 0L }
                ?: File(workDir, fileName).takeIf { it.isFile && it.length() > 0L }
                ?: File(legacyDir, fileName).takeIf { it.isFile && it.length() > 0L }
                ?: return null).let(Uri::fromFile)
        // 新任务已持久化歌单/搜索阶段的权威字段；只有旧任务缺字段时才触发昂贵的文件标签读取。
        val needsEmbeddedFallback = title.isBlank() || artist.isBlank() || album.isBlank() || durationMs <= 0L
        val tags = if (needsEmbeddedFallback) readEmbeddedMetadata(playableUri) else EmbeddedMetadata()
        return Track(
            id = "download:$id",
            // 线上解析得到的标题、歌手优先，文件标签作为旧任务或缺失字段的回退。
            title = title.ifBlank { tags.title ?: "轻音下载" },
            artist = artist.ifBlank { tags.artist ?: "未知歌手" },
            album = album.ifBlank { tags.album.orEmpty() },
            durationMs = durationMs.takeIf { it > 0L } ?: tags.durationMs,
            uri = playableUri,
            artworkUri = artworkUri?.takeIf(String::isNotBlank)?.let(Uri::parse),
            source = Track.Source.DOWNLOADED,
            localFileName = fileName,
        )
    }

    private data class EmbeddedMetadata(
        val title: String? = null,
        val artist: String? = null,
        val album: String? = null,
        val durationMs: Long = 0L,
    )

    /** 下载音频可能自带 ID3/FLAC 标签；读取它们补全旧任务，并让封面提取走同一真实文件 URI。 */
    private fun readEmbeddedMetadata(uri: Uri): EmbeddedMetadata {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            EmbeddedMetadata(
                title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE).cleanTag(),
                artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST).cleanTag(),
                album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM).cleanTag(),
                durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L,
            )
        } catch (_: Exception) {
            EmbeddedMetadata()
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun String?.cleanTag(): String? = this?.trim()?.takeIf {
        it.isNotBlank() && !it.equals("unknown", ignoreCase = true) && !it.equals("<unknown>", ignoreCase = true)
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
                            if (_tasks.value.none { it.id == id }) {
                                throw java.util.concurrent.CancellationException("下载任务已删除")
                            }
                            val count = input.read(buffer)
                            if (count < 0) break
                            buffered.write(buffer, 0, count)
                            downloaded += count
                            if (downloaded - lastReported >= 512 * 1024L || (total > 0L && downloaded == total)) {
                                lastReported = downloaded
                                updateTask(id, TaskUpdatePersistence.PROGRESS) {
                                    it.copy(
                                        status = DownloadEntry.Status.DOWNLOADING,
                                        bytesDownloaded = downloaded,
                                        totalBytes = total,
                                    )
                                }
                            }
                        }
                        buffered.flush()
                    }
                }
                require(downloaded > 0L) { "下载文件内容为空" }
                if (_tasks.value.none { it.id == id }) {
                    partFile.delete()
                    return
                }
                if (finalFile.exists()) finalFile.delete()
                require(partFile.renameTo(finalFile)) { "文件重命名失败，请检查存储空间" }
                // 先通过真实字节确认容器、规范后缀；不支持标签写入的格式会安全降级而不阻断下载。
                val prepared = prepareDownloadedAudio(finalFile, initial)
                // 完成后发布到公共目录（MediaStore / SAF / 公共 Music），让系统媒体库与外部播放器可见。
                val published = publishCompleted(prepared.file, prepared.task)
                updateTask(id) {
                    prepared.task.copy(
                        status = DownloadEntry.Status.COMPLETED,
                        bytesDownloaded = downloaded,
                        totalBytes = if (total > 0L) total else downloaded,
                        errorMessage = prepared.notice,
                        mediaUri = published?.mediaUri,
                        finalPath = published?.finalPath,
                        containerFormat = prepared.containerFormat,
                    )
                }
            }
        }.onFailure { error ->
            // 保留 .part 以支持续传，只有明确失败才保留进度文件；完成前不删除。
            val message = error.message?.takeIf(String::isNotBlank) ?: "下载处理失败：${error.javaClass.simpleName}"
            updateTask(id) { it.copy(status = DownloadEntry.Status.FAILED, errorMessage = message) }
        }
    }

    private fun downloadIdentity(title: String, artist: String): String =
        "${title.trim().lowercase()}|${artist.substringBefore(" · ").trim().lowercase()}"

    /**
     * 下载接口可能给出无扩展 API 地址、错误 format 或裸音频字节。完成下载后先依据文件签名
     * 确认容器并规范后缀；仅确认且标签库支持的容器写入内嵌信息。未知容器照常发布，
     * 避免“没有对应 Reader”阻断下载任务。
     */
    private fun prepareDownloadedAudio(file: File, task: Task): PreparedAudio {
        require(file.isFile && file.length() > 0L) { "下载音频为空，无法处理" }
        val format = AudioContainerInspector.detect(file)
        val normalizedFile = format?.let { AudioContainerInspector.normalizeExtension(file, it) } ?: file
        val normalizedTask = if (normalizedFile.name == task.fileName) task else task.copy(fileName = normalizedFile.name)
        if (format == null) {
            return PreparedAudio(
                file = normalizedFile,
                task = normalizedTask,
                containerFormat = "",
                notice = "已完成：未能确认实际音频容器，已保留原始下载字节，未写入内嵌信息",
            )
        }
        if (format !in AudioContainerInspector.tagWritableExtensions) {
            return PreparedAudio(
                file = normalizedFile,
                task = normalizedTask,
                containerFormat = format,
                notice = "已完成：已识别为 ${format.uppercase()}，该容器暂不支持安全写入内嵌信息",
            )
        }
        val notice = runCatching {
            writeAuthoritativeAudioMetadata(normalizedFile, normalizedTask)
            "已完成：已识别为 ${format.uppercase()}，标题、歌手、专辑和内嵌封面已写入并校验"
        }.getOrElse { error ->
            "已完成：已识别为 ${format.uppercase()}，内嵌信息未写入（${error.message ?: error.javaClass.simpleName}）"
        }
        return PreparedAudio(normalizedFile, normalizedTask, format, notice)
    }

    /** 以入队时保存的歌单/搜索原始字段覆盖已确认容器的标签和封面，并在落盘后回读校验。 */
    private fun writeAuthoritativeAudioMetadata(file: File, task: Task) {
        require(file.isFile && file.length() > 0L) { "下载音频为空，无法写入元数据" }
        val coverFile = task.artworkUri?.let(::downloadArtworkForTag)
        try {
            val audio = AudioFileIO.read(file)
            val tag = audio.tagOrCreateAndSetDefault
            tag.setField(FieldKey.TITLE, task.title)
            tag.setField(FieldKey.ARTIST, task.artist)
            tag.setField(FieldKey.ALBUM, task.album)
            tag.setField(FieldKey.ALBUM_ARTIST, task.artist)
            if (coverFile != null) {
                tag.deleteArtworkField()
                tag.addField(ArtworkFactory.createArtworkFromFile(coverFile))
            }
            AudioFileIO.write(audio)

            // 强制验证：不接受接口遗留标签、空标签或写入后未包含封面的音频文件。
            val verifiedTag = AudioFileIO.read(file).tag ?: error("写入后未检测到音频标签")
            require(verifiedTag.getFirst(FieldKey.TITLE) == task.title) { "标题标签校验失败" }
            require(verifiedTag.getFirst(FieldKey.ARTIST) == task.artist) { "歌手标签校验失败" }
            require(verifiedTag.getFirst(FieldKey.ALBUM) == task.album) { "专辑标签校验失败" }
            if (coverFile != null) require(verifiedTag.firstArtwork != null) { "内嵌封面校验失败" }
        } finally {
            coverFile?.delete()
        }
    }

    /** 下载任务已处于 I/O 工作线程；原始歌单封面限制在 4MB 以内并临时落盘供标签库嵌入。 */
    private fun downloadArtworkForTag(url: String): File? {
        if (!url.startsWith("https://") && !url.startsWith("http://")) return null
        return runCatching {
            val request = Request.Builder().url(url).header("User-Agent", "Qingyin/1.0").get().build()
            client.newCall(request).execute().use { response ->
                require(response.isSuccessful) { "原始封面下载失败 (HTTP ${response.code})" }
                val body = requireNotNull(response.body) { "原始封面响应为空" }
                require(body.contentLength() <= 4L * 1024L * 1024L || body.contentLength() < 0L) { "原始封面超过 4MB" }
                val bytes = body.bytes()
                require(bytes.isNotEmpty() && bytes.size <= 4 * 1024 * 1024) { "原始封面为空或过大" }
                val suffix = when {
                    response.header("Content-Type")?.contains("png", ignoreCase = true) == true -> ".png"
                    response.header("Content-Type")?.contains("webp", ignoreCase = true) == true -> ".webp"
                    else -> ".jpg"
                }
                File.createTempFile("qingyin-cover-", suffix, workDir).apply { writeBytes(bytes) }
            }
        }.getOrElse { throw IllegalStateException("无法取得歌单原始封面：${it.message}", it) }
    }

    /**
     * 把已完成的音频文件发布到用户可见目录：
     * 1) 用户选了 SAF 下载目录 → 写入该目录；
     * 2) Android 10+ → 注册进 MediaStore（公共 Music/轻音下载，系统自动索引）；
     * 3) 更早版本 → 直接写公共 Music 目录并通知媒体扫描（无权限时保留在私有工作目录兜底）。
     * 返回 PublishResult 供任务持久化；任何失败都不影响已完成文件继续可播。
     */
    private fun publishCompleted(file: File, task: Task): PublishResult? {
        val safeName = task.fileName.substringAfter('-').ifBlank { file.name }.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(120)
        val mime = mimeType(safeName)
        customDownloadFolder?.takeIf(String::isNotBlank)?.let { treeUri ->
            return runCatching {
                val root = DocumentFile.fromTreeUri(context, Uri.parse(treeUri)) ?: return@runCatching null
                val target = root.createFile(mime, safeName) ?: return@runCatching null
                val stream = context.contentResolver.openOutputStream(target.uri, "w") ?: return@runCatching null
                stream.use { out -> file.inputStream().use { input -> input.copyTo(out) } }
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
                    put(MediaStore.Audio.Media.TITLE, task.title)
                    put(MediaStore.Audio.Media.ARTIST, task.artist)
                    put(MediaStore.Audio.Media.ALBUM, task.album)
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
        "bin" -> "audio/*"
        else -> "audio/*"
    }

    private enum class TaskUpdatePersistence {
        /** 入队、状态切换、错误和完成结果必须立即落盘，以确保可恢复性。 */
        IMMEDIATE,
        /** 下载中的字节变化只节流写盘；UI 仍会即时收到新的进度。 */
        PROGRESS,
    }

    private fun updateTask(
        task: Task,
        persistence: TaskUpdatePersistence = TaskUpdatePersistence.IMMEDIATE,
    ) = synchronized(stateLock) {
        _tasks.value = _tasks.value.map { if (it.id == task.id) task else it }
        flushStateLocked(persistence)
    }

    private fun updateTask(
        id: Long,
        persistence: TaskUpdatePersistence = TaskUpdatePersistence.IMMEDIATE,
        transform: (Task) -> Task,
    ) = synchronized(stateLock) {
        _tasks.value = _tasks.value.map { if (it.id == id) transform(it) else it }
        flushStateLocked(persistence)
    }

    private fun persistLocked() = flushStateLocked(TaskUpdatePersistence.IMMEDIATE)

    private fun flushStateLocked(persistence: TaskUpdatePersistence) {
        val shouldWrite = persistence == TaskUpdatePersistence.IMMEDIATE ||
            statePersistencePolicy.shouldPersistProgress(SystemClock.elapsedRealtime())
        if (shouldWrite) writeStateLocked()
        publishEntriesLocked()
        // 进度刷新不会改变前台服务是否存活，避免每 512 KB 向主线程重复投递检查任务。
        if (persistence == TaskUpdatePersistence.IMMEDIATE) syncForegroundService()
    }

    private fun writeStateLocked() {
        runCatching {
            stateFile.writeText(JSONArray().apply {
                _tasks.value.forEach { task ->
                    put(JSONObject()
                        .put("id", task.id)
                        .put("title", task.title)
                        .put("artist", task.artist)
                        .put("album", task.album)
                        .put("artworkUri", task.artworkUri.orEmpty())
                        .put("duration", task.durationMs)
                        .put("url", task.url)
                        .put("fileName", task.fileName)
                        .put("status", task.status.name)
                        .put("referer", task.referer.orEmpty())
                        .put("bytes", task.bytesDownloaded)
                        .put("total", task.totalBytes)
                        .put("error", task.errorMessage.orEmpty())
                        .put("mediaUri", task.mediaUri.orEmpty())
                        .put("finalPath", task.finalPath.orEmpty())
                        .put("containerFormat", task.containerFormat))
                }
            }.toString())
        }
    }

    private fun publishEntriesLocked() {
        _taskEntries.value = _tasks.value.map(Task::toEntry).sortedByDescending(DownloadEntry::id)
    }

    /** 有活跃任务就拉起前台服务保活；任务全部结束就撤掉。只在边界触发。
     * 统一投递到主线程再调用服务，避免持锁线程（下载引擎/批量入队）直接
     * startForegroundService 导致的主线程调度延迟，进而触发 5 秒前台超时闪退。 */
    private fun syncForegroundService() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            syncServiceNow()
        } else {
            mainHandler.post(::syncServiceNow)
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    private fun syncServiceNow() = synchronized(stateLock) {
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

    /**
     * 旧版本会把标签库的 “No Reader associated with this extension” 直接标为下载失败。
     * 新版本已通过文件签名分流并隔离标签失败，因此仅迁移这类历史记录以自动重新下载。
     */
    private fun migrateLegacyReaderFailures(tasks: List<Task>): List<Task> {
        val readerFailure = "No Reader associated with this extension"
        return tasks.map { task ->
            if (task.status == DownloadEntry.Status.FAILED &&
                task.errorMessage?.contains(readerFailure, ignoreCase = true) == true
            ) {
                File(workDir, task.fileName).delete()
                File(workDir, "${task.fileName}.part").delete()
                task.copy(
                    status = DownloadEntry.Status.QUEUED,
                    errorMessage = "已应用新版容器检测，正在重新下载",
                    bytesDownloaded = 0L,
                    totalBytes = 0L,
                    containerFormat = "",
                )
            } else {
                task
            }
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
                    album = row.optString("album"),
                    artworkUri = row.optString("artworkUri").takeIf(String::isNotBlank),
                    durationMs = row.optLong("duration"),
                    url = url,
                    fileName = row.optString("fileName").ifBlank { "轻音下载" },
                    status = runCatching { DownloadEntry.Status.valueOf(row.optString("status")) }.getOrDefault(DownloadEntry.Status.FAILED),
                    referer = row.optString("referer").takeIf(String::isNotBlank),
                    bytesDownloaded = row.optLong("bytes"),
                    totalBytes = row.optLong("total"),
                    errorMessage = row.optString("error").takeIf(String::isNotBlank),
                    mediaUri = row.optString("mediaUri").takeIf(String::isNotBlank),
                    finalPath = row.optString("finalPath").takeIf(String::isNotBlank),
                    containerFormat = row.optString("containerFormat"),
                ))
            }
        }
    }.getOrDefault(emptyList())
}
