package im.molan.music.data.network

import android.content.Context
import android.net.Uri
import im.molan.music.model.AppSettings
import im.molan.music.model.PlaylistDetail
import im.molan.music.model.PlaylistSummary
import im.molan.music.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

class PlaylistRepository(private val context: Context, private val ncm: NcmRepository) {
    private val cacheDir = File(context.filesDir, "playlists-v2").apply { mkdirs() }
    private val listTtlMs = 6L * 60 * 60 * 1000
    private val detailTtlMs = 12L * 60 * 60 * 1000

    suspend fun playlists(settings: AppSettings, userId: Long, force: Boolean = false): List<PlaylistSummary> = withContext(Dispatchers.IO) {
        if (!force) readList(userId)?.takeIf { it.first }?.second?.let { return@withContext it }
        val result = ncm.userPlaylists(settings, userId)
        if (result.isNotEmpty()) writeList(userId, result)
        result
    }

    suspend fun detail(settings: AppSettings, playlistId: String, force: Boolean = false): PlaylistDetail = withContext(Dispatchers.IO) {
        if (!force) readDetail(playlistId)?.takeIf { it.first }?.second?.let { return@withContext it }
        val result = ncm.playlistDetail(settings, playlistId)
        writeDetail(playlistId, result)
        result
    }

    private fun readList(userId: Long): Pair<Boolean, List<PlaylistSummary>>? = runCatching {
        val json = JSONObject(fileFor("list:$userId").readText())
        val fresh = System.currentTimeMillis() - json.optLong("createdAt") <= listTtlMs
        fresh to decodeSummaries(json.optJSONArray("items") ?: JSONArray())
    }.getOrNull()

    private fun writeList(userId: Long, items: List<PlaylistSummary>) {
        fileFor("list:$userId").writeText(JSONObject()
            .put("createdAt", System.currentTimeMillis())
            .put("items", encodeSummaries(items))
            .toString())
    }

    private fun readDetail(playlistId: String): Pair<Boolean, PlaylistDetail>? = runCatching {
        val json = JSONObject(fileFor("detail:$playlistId").readText())
        val fresh = System.currentTimeMillis() - json.optLong("createdAt") <= detailTtlMs
        val summary = decodeSummary(json.getJSONObject("summary"))
        fresh to PlaylistDetail(summary, decodeTracks(json.optJSONArray("tracks") ?: JSONArray()))
    }.getOrNull()

    private fun writeDetail(playlistId: String, detail: PlaylistDetail) {
        fileFor("detail:$playlistId").writeText(JSONObject()
            .put("createdAt", System.currentTimeMillis())
            .put("summary", encodeSummary(detail.summary))
            .put("tracks", encodeTracks(detail.tracks))
            .toString())
    }

    private fun encodeSummaries(items: List<PlaylistSummary>) = JSONArray().apply { items.forEach { put(encodeSummary(it)) } }
    private fun decodeSummaries(items: JSONArray) = buildList { for (index in 0 until items.length()) items.optJSONObject(index)?.let { add(decodeSummary(it)) } }
    private fun encodeSummary(item: PlaylistSummary) = JSONObject().put("id", item.id).put("name", item.name).put("cover", item.coverUri?.toString().orEmpty()).put("count", item.trackCount).put("creator", item.creator)
    private fun decodeSummary(json: JSONObject) = PlaylistSummary(json.optString("id"), json.optString("name"), json.optString("cover").takeIf(String::isNotBlank)?.let(Uri::parse), json.optInt("count"), json.optString("creator"))

    private fun encodeTracks(items: List<Track>) = JSONArray().apply { items.forEach { track ->
        put(JSONObject().put("id", track.id).put("title", track.title).put("artist", track.artist).put("album", track.album).put("duration", track.durationMs).put("cover", track.artworkUri?.toString().orEmpty()).put("source", track.source.name))
    } }
    private fun decodeTracks(items: JSONArray) = buildList { for (index in 0 until items.length()) {
        val json = items.optJSONObject(index) ?: continue
        add(Track(json.optString("id"), json.optString("title"), json.optString("artist"), json.optString("album"), json.optLong("duration"), artworkUri = json.optString("cover").takeIf(String::isNotBlank)?.let(Uri::parse), source = runCatching { Track.Source.valueOf(json.optString("source")) }.getOrDefault(Track.Source.NETEASE)))
    } }

    private fun fileFor(key: String) = File(cacheDir, MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it) } + ".json")
}
