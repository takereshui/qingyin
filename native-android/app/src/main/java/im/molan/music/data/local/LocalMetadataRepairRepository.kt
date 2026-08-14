package im.molan.music.data.local

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import im.molan.music.model.LocalMetadataIssue
import im.molan.music.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.images.ArtworkFactory
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * 为用户主动选中的本地文件补齐标签和内嵌封面。
 *
 * 不信任本地文件的现有标签，也不信任下载/播放接口的元数据；写入始终使用由
 * 歌单或搜索返回并经严格匹配确认的原始 Track。先在私有工作区完成写入和回读，
 * 再覆盖用户授权的原文件；覆盖中断时尝试从备份恢复，避免留下半写入文件。
 */
class LocalMetadataRepairRepository(private val context: Context) {
    data class RepairResult(val local: Track, val source: Track)
    enum class RepairStep { WRITING_TAGS, VERIFYING, REPLACING_FILE }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()
    private val workDir = File(context.cacheDir, "local-metadata-repair").apply { mkdirs() }

    suspend fun findIssues(
        tracks: List<Track>,
        onProgress: (completed: Int, total: Int, current: Track) -> Unit = { _, _, _ -> },
    ): List<LocalMetadataIssue> = withContext(Dispatchers.IO) {
        val localTracks = tracks.filter { it.source == Track.Source.LOCAL && it.uri != null }
        buildList {
            localTracks.forEachIndexed { index, track ->
                onProgress(index, localTracks.size, track)
                inspect(track)?.let(::add)
                onProgress(index + 1, localTracks.size, track)
            }
        }
    }

    private fun inspect(track: Track): LocalMetadataIssue? {
        val uri = track.uri ?: return null
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
            val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
            val album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
            val artwork = retriever.embeddedPicture
            LocalMetadataIssue(
                track = track,
                missingTitle = !isMeaningful(title),
                missingArtist = !isMeaningful(artist),
                missingAlbum = !isMeaningful(album),
                missingArtwork = artwork == null || artwork.isEmpty(),
            ).takeIf { it.missingTitle || it.missingArtist || it.missingAlbum || it.missingArtwork }
        } catch (_: Exception) {
            // 无法读取标签的音频仍列出，供用户选择后尝试以原始信息重新写入。
            LocalMetadataIssue(track, missingTitle = true, missingArtist = true, missingAlbum = true, missingArtwork = true)
        } finally {
            runCatching { retriever.release() }
        }
    }

    /**
     * 使用已确认的原始线上曲目修复用户明确选择的本地文件。此函数不会自动运行，
     * 仅由“修复所选”操作触发。
     */
    suspend fun repair(
        local: Track,
        source: Track,
        onStep: (RepairStep) -> Unit = {},
    ): RepairResult = withContext(Dispatchers.IO) {
        val targetUri = requireNotNull(local.uri) { "本地文件地址不可用" }
        require(local.source == Track.Source.LOCAL) { "只能修复本地音乐" }
        require(isMeaningful(source.title)) { "未获得可靠的原始歌曲标题" }
        require(isMeaningful(source.artist)) { "未获得可靠的原始歌手信息" }
        require(isMeaningful(source.album)) { "未获得可靠的原始专辑信息" }
        require(!source.artworkUri?.toString().isNullOrBlank()) { "未获得可靠的原始封面" }

        val extension = local.localFileName
            ?.substringAfterLast('.', "")
            ?.lowercase()
            ?.takeIf { it in setOf("mp3", "flac", "m4a", "aac", "ogg", "opus", "wav") }
            ?: "mp3"
        val backup = File.createTempFile("qingyin-repair-backup-", ".${extension}", workDir)
        val tagged = File.createTempFile("qingyin-repair-tagged-", ".${extension}", workDir)
        try {
            context.contentResolver.openInputStream(targetUri)?.use { input ->
                FileOutputStream(backup).use { output -> input.copyTo(output) }
            } ?: error("无法读取本地音频，请重新授权文件访问")
            backup.copyTo(tagged, overwrite = true)
            onStep(RepairStep.WRITING_TAGS)
            writeAuthoritativeMetadata(tagged, source) { onStep(RepairStep.VERIFYING) }
            try {
                onStep(RepairStep.REPLACING_FILE)
                overwriteUri(targetUri, tagged)
            } catch (writeError: Throwable) {
                // 覆盖失败时将原文件恢复，避免用户文件停留在截断或半写入状态。
                runCatching { overwriteUri(targetUri, backup) }
                throw writeError
            }
            RepairResult(local, source)
        } finally {
            backup.delete()
            tagged.delete()
        }
    }

    private fun overwriteUri(uri: Uri, source: File) {
        context.contentResolver.openOutputStream(uri, "wt")?.use { output ->
            source.inputStream().use { input -> input.copyTo(output) }
        } ?: error("没有写入权限；请在系统授权弹窗中允许修改所选音频")
    }

    private fun writeAuthoritativeMetadata(file: File, source: Track, onVerifying: () -> Unit) {
        val cover = source.artworkUri?.toString()?.let(::downloadArtwork) ?: error("未获得可靠的原始封面")
        try {
            val audio = AudioFileIO.read(file)
            val tag = audio.tagOrCreateAndSetDefault
            tag.setField(FieldKey.TITLE, source.title)
            tag.setField(FieldKey.ARTIST, source.artist)
            tag.setField(FieldKey.ALBUM, source.album)
            tag.setField(FieldKey.ALBUM_ARTIST, source.artist)
            tag.deleteArtworkField()
            tag.addField(ArtworkFactory.createArtworkFromFile(cover))
            AudioFileIO.write(audio)

            onVerifying()
            val verified = AudioFileIO.read(file).tag ?: error("写入后未检测到音频标签")
            require(verified.getFirst(FieldKey.TITLE) == source.title) { "标题标签校验失败" }
            require(verified.getFirst(FieldKey.ARTIST) == source.artist) { "歌手标签校验失败" }
            require(verified.getFirst(FieldKey.ALBUM) == source.album) { "专辑标签校验失败" }
            require(verified.firstArtwork?.binaryData?.isNotEmpty() == true) { "内嵌封面校验失败" }
        } finally {
            cover.delete()
        }
    }

    private fun downloadArtwork(url: String): File {
        require(url.startsWith("https://") || url.startsWith("http://")) { "原始封面地址无效" }
        return client.newCall(Request.Builder().url(url).header("User-Agent", "Qingyin/1.0").get().build()).execute().use { response ->
            require(response.isSuccessful) { "原始封面下载失败 (HTTP ${response.code})" }
            val body = requireNotNull(response.body) { "原始封面响应为空" }
            require(body.contentLength() < 0 || body.contentLength() <= 4L * 1024L * 1024L) { "原始封面超过 4MB" }
            val bytes = body.bytes()
            require(bytes.isNotEmpty() && bytes.size <= 4 * 1024 * 1024) { "原始封面为空或过大" }
            val suffix = when {
                response.header("Content-Type")?.contains("png", ignoreCase = true) == true -> ".png"
                response.header("Content-Type")?.contains("webp", ignoreCase = true) == true -> ".webp"
                else -> ".jpg"
            }
            File.createTempFile("qingyin-repair-cover-", suffix, workDir).apply { writeBytes(bytes) }
        }
    }

    private fun isMeaningful(value: String?): Boolean {
        val text = value?.trim().orEmpty()
        if (text.isBlank()) return false
        return text.lowercase() !in setOf("unknown", "<unknown>", "未知歌曲", "未知歌手", "未知专辑", "unknown artist", "unknown album", "null", "n/a", "none")
    }
}
