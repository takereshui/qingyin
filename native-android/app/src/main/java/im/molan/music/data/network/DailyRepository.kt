package im.molan.music.data.network

import android.content.Context
import android.net.Uri
import im.molan.music.model.AppSettings
import im.molan.music.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class DailyRepository(private val context: Context, private val ncm: NcmRepository) {
    private val cacheFile = File(context.filesDir, "daily-v2.json")
    private val ttlMs = 10L * 60 * 1000

    /** 返回本地持久化的每日推荐，不受缓存时效限制，供离线优先展示。 */
    suspend fun cached(): List<Track> = withContext(Dispatchers.IO) { read()?.second.orEmpty() }

    suspend fun get(settings: AppSettings, force: Boolean = false): List<Track> = withContext(Dispatchers.IO) {
        if (!force) read()?.takeIf { it.first }?.second?.let { return@withContext it }
        val tracks = ncm.dailySongs(settings)
        if (tracks.isNotEmpty()) write(tracks)
        tracks
    }

    private fun read(): Pair<Boolean, List<Track>>? = runCatching {
        val json = JSONObject(cacheFile.readText())
        val fresh = System.currentTimeMillis() - json.optLong("createdAt") <= ttlMs
        val list = json.optJSONArray("tracks") ?: JSONArray()
        fresh to buildList {
            for (i in 0 until list.length()) {
                val item = list.optJSONObject(i) ?: continue
                add(
                    Track(
                        id = item.optString("id"),
                        title = item.optString("title"),
                        artist = item.optString("artist"),
                        album = item.optString("album"),
                        durationMs = item.optLong("duration"),
                        artworkUri = item.optString("artwork").takeIf(String::isNotBlank)?.let(Uri::parse),
                        source = Track.Source.NETEASE,
                    ),
                )
            }
        }
    }.getOrNull()

    private fun write(tracks: List<Track>) {
        val array = JSONArray()
        tracks.forEach { track ->
            array.put(JSONObject()
                .put("id", track.id)
                .put("title", track.title)
                .put("artist", track.artist)
                .put("album", track.album)
                .put("duration", track.durationMs)
                .put("artwork", track.artworkUri?.toString().orEmpty()))
        }
        cacheFile.writeText(JSONObject().put("createdAt", System.currentTimeMillis()).put("tracks", array).toString())
    }
}
