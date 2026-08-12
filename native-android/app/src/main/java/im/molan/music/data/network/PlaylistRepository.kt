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

class PlaylistRepository(
    private val context: Context,
    private val ncm: NcmRepository,
    private val qq: QqRepository = QqRepository(),
) {
    private val cacheDir = File(context.filesDir, "playlists-v2").apply { mkdirs() }
    private val listTtlMs = 6L * 60 * 60 * 1000
    private val detailTtlMs = 12L * 60 * 60 * 1000

    /** 供界面优先展示本地持久化的歌单目录；即使缓存已过期也可离线使用。 */
    suspend fun cachedPlaylists(userId: Long): List<PlaylistSummary> = withContext(Dispatchers.IO) {
        readList(userId)?.second.orEmpty()
    }

    /** 供界面优先展示本地持久化的歌单详情与曲目清单。 */
    suspend fun cachedDetail(playlistId: String): PlaylistDetail? = withContext(Dispatchers.IO) {
        readDetail(playlistId)?.second
    }

    suspend fun playlists(settings: AppSettings, userId: Long, force: Boolean = false): List<PlaylistSummary> = withContext(Dispatchers.IO) {
        val cached = readList(userId)?.second.orEmpty()
        if (!force && cached.isNotEmpty()) return@withContext cached
        runCatching { ncm.userPlaylists(settings, userId) }
            .onSuccess { result -> if (result.isNotEmpty()) writeList(userId, result) }
            .getOrElse { error ->
                if (cached.isNotEmpty()) cached else throw error
            }
    }

    suspend fun detail(settings: AppSettings, playlist: PlaylistSummary, force: Boolean = false): PlaylistDetail = withContext(Dispatchers.IO) {
        val cached = readDetail(playlist.id)?.second
        if (!force && cached != null) return@withContext cached
        runCatching {
            when (playlist.source) {
                Track.Source.QQ -> qq.publicPlaylist(playlist.id)
                else -> ncm.playlistDetail(settings, playlist.id)
            }
        }.onSuccess { result -> writeDetail(playlist.id, result) }
            .getOrElse { error -> cached ?: throw error }
    }

    suspend fun import(settings: AppSettings, source: Track.Source, input: String): PlaylistDetail = withContext(Dispatchers.IO) {
        val result = when (source) {
            Track.Source.QQ -> qq.publicPlaylist(input)
            else -> ncm.playlistDetail(settings, input.extractNcmPlaylistId())
        }
        writeDetail(result.summary.id, result)
        result
    }

    suspend fun cachedImported(ids: List<String>): List<PlaylistSummary> = withContext(Dispatchers.IO) {
        ids.distinct().mapNotNull { id -> readDetail(id)?.second?.summary }
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
    private fun encodeSummary(item: PlaylistSummary) = JSONObject().put("id", item.id).put("name", item.name).put("cover", item.coverUri?.toString().orEmpty()).put("count", item.trackCount).put("creator", item.creator).put("source", item.source.name)
    private fun decodeSummary(json: JSONObject) = PlaylistSummary(
        id = json.optString("id"),
        name = json.optString("name"),
        coverUri = json.optString("cover").takeIf(String::isNotBlank)?.let(Uri::parse),
        trackCount = json.optInt("count"),
        creator = json.optString("creator"),
        source = runCatching { Track.Source.valueOf(json.optString("source")) }.getOrDefault(Track.Source.NETEASE),
    )

    private fun encodeTracks(items: List<Track>) = JSONArray().apply { items.forEach { track ->
        put(JSONObject().put("id", track.id).put("title", track.title).put("artist", track.artist).put("album", track.album).put("duration", track.durationMs).put("cover", track.artworkUri?.toString().orEmpty()).put("source", track.source.name).put("qqMid", track.qqMid.orEmpty()))
    } }
    private fun decodeTracks(items: JSONArray) = buildList { for (index in 0 until items.length()) {
        val json = items.optJSONObject(index) ?: continue
        add(Track(
            id = json.optString("id"),
            title = json.optString("title"),
            artist = json.optString("artist"),
            album = json.optString("album"),
            durationMs = json.optLong("duration"),
            artworkUri = json.optString("cover").takeIf(String::isNotBlank)?.let(Uri::parse),
            source = runCatching { Track.Source.valueOf(json.optString("source")) }.getOrDefault(Track.Source.NETEASE),
            qqMid = json.optString("qqMid").takeIf(String::isNotBlank),
        ))
    } }

    private fun String.extractNcmPlaylistId(): String {
        val fromUrl = Regex("(?:playlist\\?id=|playlist/)([0-9]{5,})", RegexOption.IGNORE_CASE).find(this)?.groupValues?.getOrNull(1)
        return fromUrl ?: Regex("[0-9]{5,}").find(this)?.value.orEmpty().also { require(it.isNotBlank()) { "请输入网易云歌单链接或纯数字歌单 ID" } }
    }

    private fun fileFor(key: String) = File(cacheDir, MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it) } + ".json")
}
