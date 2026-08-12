package im.molan.music.data.download

import android.app.DownloadManager
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.content.ContentUris
import im.molan.music.model.DownloadEntry
import im.molan.music.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DownloadRepository(private val context: Context) {
    private val manager: DownloadManager = context.getSystemService(DownloadManager::class.java)
    private val preferences = context.getSharedPreferences("qingyin_downloads", Context.MODE_PRIVATE)
    private val savedIds: Set<Long> get() = preferences.getStringSet("ids", emptySet()).orEmpty().mapNotNull(String::toLongOrNull).toSet()

    private fun saveId(id: Long) {
        preferences.edit().putStringSet("ids", (savedIds + id).map(Long::toString).toSet()).apply()
    }

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
        return manager.enqueue(request).also(::saveId)
    }

    suspend fun status(id: Long): DownloadEntry? = withContext(Dispatchers.IO) {
        manager.query(DownloadManager.Query().setFilterById(id))?.use { cursor ->
            if (cursor.moveToFirst()) cursor.toEntry() else null
        }
    }

    suspend fun entries(): List<DownloadEntry> = withContext(Dispatchers.IO) {
        val ids = savedIds.toLongArray()
        if (ids.isEmpty()) return@withContext emptyList()
        manager.query(DownloadManager.Query().setFilterById(*ids))?.use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.toEntry()) }.sortedByDescending(DownloadEntry::id)
        } ?: emptyList()
    }

    suspend fun downloadedTracks(): List<Track> = withContext(Dispatchers.IO) {
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
        )
        val selection: String
        val args: Array<String>
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            selection = "${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ?"
            args = arrayOf("Music/轻音/%")
        } else {
            @Suppress("DEPRECATION")
            run {
                selection = "${MediaStore.Audio.Media.DATA} LIKE ?"
                args = arrayOf("%/Music/轻音/%")
            }
        }
        context.contentResolver.query(collection, projection, selection, args, "${MediaStore.Audio.Media.DATE_ADDED} DESC")?.use { cursor ->
            buildList {
                val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val durationIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idIndex)
                    add(
                        Track(
                            id = "download:$id",
                            title = cursor.getString(titleIndex).orEmpty().ifBlank { "已下载音乐" },
                            artist = cursor.getString(artistIndex).orEmpty().ifBlank { "未知歌手" },
                            album = cursor.getString(albumIndex).orEmpty(),
                            durationMs = cursor.getLong(durationIndex).coerceAtLeast(0L),
                            uri = ContentUris.withAppendedId(collection, id),
                            source = Track.Source.DOWNLOADED,
                        ),
                    )
                }
            }
        } ?: emptyList()
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
            fileName = getString(getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_FILENAME)).orEmpty().ifBlank {
                getString(getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI)).orEmpty()
            },
        )
    }
}
