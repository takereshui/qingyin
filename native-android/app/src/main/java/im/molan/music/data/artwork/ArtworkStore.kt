package im.molan.music.data.artwork

import android.content.Context
import android.media.MediaMetadataRetriever
import im.molan.music.data.db.ArtworkDao
import im.molan.music.data.db.ArtworkEntity
import im.molan.music.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * 封面本地化：网络封面加载成功后落盘到应用私有 covers 目录，
 * 并以 URL→本地路径 记入 Room；之后离线也走本地文件引用。
 * 本地文件音源（MediaStore / SAF）优先提取文件内嵌封面，同样落盘入库，一次提取永久复用。
 */
class ArtworkStore(context: Context, private val dao: ArtworkDao) {
    private val appContext = context.applicationContext
    private val coversDir = File(appContext.filesDir, "covers").apply { mkdirs() }

    fun fileFor(url: String): File = File(coversDir, sha256(url).take(24) + ".jpg")

    suspend fun localPathFor(url: String): File? = withContext(Dispatchers.IO) {
        dao.byUrl(url)?.filePath?.let(::File)?.takeIf { it.isFile && it.length() > 0L }
    }

    suspend fun remember(url: String, file: File) = withContext(Dispatchers.IO) {
        dao.upsert(ArtworkEntity(url = url, filePath = file.absolutePath, updatedAt = System.currentTimeMillis()))
    }

    /**
     * 本地音源的封面文件：优先内嵌封面（提取一次即永久缓存），
     * 拿不到时再回退到 MediaStore 专辑封面 URI 由图片来源方处理。
     */
    suspend fun localEmbeddedArtwork(track: Track): File? = withContext(Dispatchers.IO) {
        val uri = track.uri ?: return@withContext null
        val key = "embedded:${track.id}"
        dao.byUrl(key)?.filePath?.let(::File)?.takeIf { it.isFile && it.length() > 0L }?.let { return@withContext it }
        val bytes = extractEmbeddedPicture(uri) ?: return@withContext null
        val file = File(coversDir, sha256(key).take(24) + ".jpg")
        runCatching { if (!file.isFile) file.writeBytes(bytes) }
        dao.upsert(ArtworkEntity(url = key, filePath = file.absolutePath, updatedAt = System.currentTimeMillis()))
        file.takeIf { it.isFile && it.length() > 0L }
    }

    private fun extractEmbeddedPicture(uri: android.net.Uri): ByteArray? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(appContext, uri)
            retriever.embeddedPicture
        } catch (_: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    fun clearLocalArtwork() {
        coversDir.listFiles()?.forEach { runCatching { it.delete() } }
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}