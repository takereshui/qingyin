package im.molan.music.data.download

import android.app.DownloadManager
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Environment
import im.molan.music.model.DownloadEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DownloadRepository(private val context: Context) {
    private val manager: DownloadManager = context.getSystemService(DownloadManager::class.java)

    fun enqueue(url: String, title: String, artist: String, fileName: String): Long {
        require(url.startsWith("https://") || url.startsWith("http://")) { "下载地址无效" }
        val safeName = fileName
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .take(120)
            .ifBlank { "轻音下载" }
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle(title.ifBlank { "轻音下载" })
            .setDescription(artist)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setAllowedOverMetered(true)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_MUSIC, "轻音/$safeName")
        return manager.enqueue(request)
    }

    suspend fun status(id: Long): DownloadEntry? = withContext(Dispatchers.IO) {
        manager.query(DownloadManager.Query().setFilterById(id))?.use { cursor ->
            if (cursor.moveToFirst()) cursor.toEntry() else null
        }
    }

    private fun Cursor.toEntry(): DownloadEntry {
        val state = when (getInt(getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))) {
            DownloadManager.STATUS_PENDING -> DownloadEntry.Status.QUEUED
            DownloadManager.STATUS_RUNNING -> DownloadEntry.Status.DOWNLOADING
            DownloadManager.STATUS_PAUSED -> DownloadEntry.Status.PAUSED
            DownloadManager.STATUS_SUCCESSFUL -> DownloadEntry.Status.COMPLETED
            DownloadManager.STATUS_FAILED -> DownloadEntry.Status.FAILED
            else -> DownloadEntry.Status.MISSING
        }
        return DownloadEntry(
            id = getLong(getColumnIndexOrThrow(DownloadManager.COLUMN_ID)),
            title = getString(getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE)).orEmpty(),
            artist = getString(getColumnIndexOrThrow(DownloadManager.COLUMN_DESCRIPTION)).orEmpty(),
            status = state,
            bytesDownloaded = getLong(getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
            totalBytes = getLong(getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)),
            fileName = getString(getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_FILENAME)).orEmpty(),
        )
    }
}
