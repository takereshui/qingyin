package im.molan.music.data.artwork

import android.content.Context
import im.molan.music.data.db.ArtworkDao
import im.molan.music.data.db.ArtworkEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * 封面本地化：网络封面加载成功后落盘到应用私有 covers 目录，
 * 并以 URL→本地路径 记入 Room；之后离线也走本地文件引用。
 */
class ArtworkStore(context: Context, private val dao: ArtworkDao) {
    private val coversDir = File(context.filesDir, "covers").apply { mkdirs() }

    fun fileFor(url: String): File = File(coversDir, sha256(url).take(24) + ".jpg")

    suspend fun localPathFor(url: String): File? = withContext(Dispatchers.IO) {
        dao.byUrl(url)?.filePath?.let(::File)?.takeIf { it.isFile && it.length() > 0L }
    }

    suspend fun remember(url: String, file: File) = withContext(Dispatchers.IO) {
        dao.upsert(ArtworkEntity(url = url, filePath = file.absolutePath, updatedAt = System.currentTimeMillis()))
    }

    fun clearLocalArtwork() {
        coversDir.listFiles()?.forEach { runCatching { it.delete() } }
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}