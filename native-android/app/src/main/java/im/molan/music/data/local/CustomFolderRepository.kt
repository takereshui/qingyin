package im.molan.music.data.local

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import im.molan.music.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

class CustomFolderRepository(private val context: Context) {
    /** MediaMetadataRetriever 单文件初始化较重，固定并发提取元数据，大目录提速明显。 */
    private val metadataSemaphore = Semaphore(4)

    suspend fun scan(treeUri: Uri, previous: Map<String, Track> = emptyMap(), maxDepth: Int = 24): List<Track> = withContext(Dispatchers.IO) {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return@withContext emptyList()
        val files = mutableListOf<DocumentFile>()
        walk(root, 0, maxDepth, files)
        // 只提取新出现的文件，已缓存的音轨（时长/标签）直接复用，重启与重复扫描零 retriever 开销。
        val toExtract = files.filter { "saf:${it.uri}" !in previous }
        val newTracks = toExtract.map { file ->
            async { metadataSemaphore.withPermit { file.toTrack() } }
        }.awaitAll()
        val kept = files.mapNotNull { file -> previous["saf:${file.uri}"] }
        (kept + newTracks).sortedWith(compareBy<Track> { it.title.lowercase() }.thenBy { it.artist.lowercase() })
    }

    private fun walk(folder: DocumentFile, depth: Int, maxDepth: Int, out: MutableList<DocumentFile>) {
        if (depth > maxDepth || !folder.isDirectory) return
        folder.listFiles().forEach { file ->
            when {
                file.isDirectory -> walk(file, depth + 1, maxDepth, out)
                file.isFile && file.isMusic() -> out += file
            }
        }
    }

    private fun DocumentFile.isMusic(): Boolean {
        val lower = name.orEmpty().lowercase()
        return type?.startsWith("audio/") == true || lower.endsWith(".mp3") || lower.endsWith(".flac") ||
            lower.endsWith(".m4a") || lower.endsWith(".aac") || lower.endsWith(".ogg") || lower.endsWith(".opus") || lower.endsWith(".wav")
    }

    private fun DocumentFile.toTrack(): Track {
        val metadata = MediaMetadataRetriever()
        return try {
            metadata.setDataSource(context, uri)
            val title = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE).orEmpty().ifBlank { name.orEmpty().substringBeforeLast('.') }
            val artist = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST).orEmpty().ifBlank { "未知歌手" }
            val album = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM).orEmpty()
            val duration = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            Track("saf:$uri", title, artist, album, duration, uri = uri, source = Track.Source.LOCAL, localFileName = name.orEmpty())
        } catch (_: Exception) {
            Track("saf:$uri", name.orEmpty().substringBeforeLast('.'), uri = uri, source = Track.Source.LOCAL, localFileName = name.orEmpty())
        } finally {
            runCatching { metadata.release() }
        }
    }
}
