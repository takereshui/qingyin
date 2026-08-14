package im.molan.music.data.local

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import im.molan.music.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class LocalMusicRepository(private val context: Context) {
    suspend fun scanMediaStore(previous: Map<String, Track> = emptyMap()): List<Track> = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DISPLAY_NAME,
        )
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND " +
            "${MediaStore.Audio.Media.DURATION} > 0 AND ${MediaStore.Audio.Media.SIZE} > 0"
        resolver.query(
            collection,
            projection,
            selection,
            null,
            "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC",
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val displayNameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            buildList {
                while (cursor.moveToNext()) {
                    val mediaId = cursor.getLong(idCol)
                    val uri = ContentUris.withAppendedId(collection, mediaId)
                    // 文件未变直接复用上次索引缓存，免去逐行重建 Track（重启后 MediaStore 扫描基本零构造）。
                    val cacheHit = previous[uri.toString()]
                    if (cacheHit != null && cacheHit.id == "local:$mediaId") {
                        add(cacheHit)
                        continue
                    }
                    val albumId = cursor.getLong(albumIdCol)
                    val artwork = ContentUris.withAppendedId(
                        MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI,
                        albumId,
                    )
                    add(
                        Track(
                            id = "local:$mediaId",
                            title = cursor.getString(titleCol).orEmpty().ifBlank { "未知歌曲" },
                            artist = cursor.getString(artistCol).orEmpty().ifBlank { "未知歌手" },
                            album = cursor.getString(albumCol).orEmpty(),
                            durationMs = cursor.getLong(durationCol),
                            uri = uri,
                            artworkUri = artwork,
                            source = Track.Source.LOCAL,
                            localFileName = cursor.getString(displayNameCol).orEmpty(),
                        ),
                    )
                }
            }
        } ?: emptyList()
    }

    fun canReadMedia(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        context.checkSelfPermission(android.Manifest.permission.READ_MEDIA_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
    } else {
        context.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private fun indexFile() = File(context.filesDir, "local-music-index.json")

    /** 将最近一次扫描的本地音乐索引落盘，冷启动时先展示缓存再后台重扫，避免“扫描过又没了”。 */
    suspend fun saveIndex(tracks: List<Track>) = withContext(Dispatchers.IO) {
        runCatching {
            indexFile().writeText(JSONObject()
                .put("createdAt", System.currentTimeMillis())
                .put("items", JSONArray().apply {
                    tracks.forEach { put(encodeTrack(it)) }
                })
                .toString())
        }
    }

    /** 读取上次扫描的本地音乐索引；文件缺失或损坏时返回空列表。 */
    suspend fun loadIndex(): List<Track> = withContext(Dispatchers.IO) {
        runCatching {
            val json = JSONObject(indexFile().readText())
            val items = json.optJSONArray("items") ?: JSONArray()
            buildList { for (i in 0 until items.length()) items.optJSONObject(i)?.let { decodeTrack(it)?.let(::add) } }
        }.getOrDefault(emptyList())
    }

    private fun encodeTrack(track: Track) = JSONObject()
        .put("id", track.id)
        .put("title", track.title)
        .put("artist", track.artist)
        .put("album", track.album)
        .put("duration", track.durationMs)
        .put("uri", track.uri?.toString().orEmpty())
        .put("artwork", track.artworkUri?.toString().orEmpty())
        .put("file", track.localFileName.orEmpty())

    private fun decodeTrack(json: JSONObject): Track? {
        val id = json.optString("id")
        if (id.isBlank()) return null
        return Track(
            id = id,
            title = json.optString("title"),
            artist = json.optString("artist").ifBlank { "未知歌手" },
            album = json.optString("album"),
            durationMs = json.optLong("duration"),
            uri = json.optString("uri").takeIf { it.isNotBlank() }?.let(Uri::parse),
            artworkUri = json.optString("artwork").takeIf { it.isNotBlank() }?.let(Uri::parse),
            source = Track.Source.LOCAL,
            localFileName = json.optString("file").takeIf { it.isNotBlank() },
        )
    }
}
