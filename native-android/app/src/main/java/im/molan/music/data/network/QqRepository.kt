package im.molan.music.data.network

import android.net.Uri
import im.molan.music.model.AppSettings
import im.molan.music.model.PlaylistDetail
import im.molan.music.model.PlaylistSummary
import im.molan.music.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * QQ 单曲搜索和音源由 ChKSz API 提供。QQ 质量使用其独立的 size 参数，绝不使用网易云 level。
 * QQ 歌单链接导入只读取公开歌单详情，不需要 QQ 登录，也不会读取用户账户或 Cookie。
 */
class QqRepository(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(18, TimeUnit.SECONDS)
        .build(),
) {
    data class ResolvedQqTrack(val track: Track, val lyric: String)

    suspend fun search(settings: AppSettings, keyword: String, limit: Int = 30): List<Track> = withContext(Dispatchers.IO) {
        val payload = requestChksz(settings, mapOf("msg" to keyword, "num" to limit.coerceIn(1, 50).toString(), "type" to "json"))
        val rows = payload.optJSONArray("list") ?: JSONArray()
        buildList {
            for (index in 0 until rows.length()) {
                val item = rows.optJSONObject(index) ?: continue
                val mid = item.optString("mid").trim()
                if (mid.isBlank()) continue
                add(
                    Track(
                        id = "qq:$mid",
                        title = item.optString("name").ifBlank { "未知歌曲" },
                        artist = item.optString("singer").ifBlank { "未知歌手" },
                        album = item.optString("album"),
                        source = Track.Source.QQ,
                        qqMid = mid,
                    ),
                )
            }
        }
    }

    suspend fun resolve(settings: AppSettings, track: Track): ResolvedQqTrack = withContext(Dispatchers.IO) {
        require(track.source == Track.Source.QQ) { "该曲目不是 QQ 音源" }
        val mid = track.qqMid.orEmpty().ifBlank { track.id.removePrefix("qq:") }
        require(mid.isNotBlank()) { "QQ 曲目缺少 MID" }
        val payload = requestChksz(settings, mapOf("mid" to mid, "size" to settings.qqQuality.wireValue, "type" to "json"))
        val url = payload.optString("url").replace("\\/", "/").replaceFirst("http://", "https://")
        require(url.startsWith("https://")) { payload.optString("msg").ifBlank { "QQ 未提供 ${settings.qqQuality.label} 音源" } }
        val actual = AppSettings.QqQuality.entries.firstOrNull {
            it.wireValue.equals(payload.optString("bitrate"), ignoreCase = true) || it.wireValue.equals(payload.optString("size"), ignoreCase = true)
        }
        val returnedQuality = payload.optString("bitrate").ifBlank { payload.optString("format") }
        require(actual == settings.qqQuality) {
            "QQ 当前返回“$returnedQuality”而非“${settings.qqQuality.label}”；已取消解析，避免静默降档。"
        }
        val format = payload.optString("format").lowercase().takeIf(String::isNotBlank)
            ?: url.substringBefore('?').substringAfterLast('.', "mp3")
        val cover = payload.optString("cover").takeIf(String::isNotBlank)?.let(Uri::parse) ?: track.artworkUri
        ResolvedQqTrack(
            track = track.copy(
                title = payload.optString("name").ifBlank { track.title },
                artist = payload.optString("singer").ifBlank { track.artist },
                album = payload.optString("album").ifBlank { track.album },
                artworkUri = cover,
                remoteUrl = url,
                resolvedQqQuality = actual,
                audioExtension = format,
                qqMid = mid,
            ),
            lyric = payload.optString("lrc"),
        )
    }

    /**
     * 导入公开 QQ 歌单分享链接或纯数字歌单 ID。该读取不使用 QQ 登录和 Cookie。
     */
    suspend fun publicPlaylist(input: String): PlaylistDetail = withContext(Dispatchers.IO) {
        val id = extractPlaylistId(input)
        val endpoint = "https://i.y.qq.com/qzone-music/fcg-bin/fcg_ucc_getcdinfo_byids_cp.fcg"
        val requestUrl = endpoint.toHttpUrl().newBuilder()
            .addQueryParameter("type", "1")
            .addQueryParameter("json", "1")
            .addQueryParameter("utf8", "1")
            .addQueryParameter("onlysong", "0")
            .addQueryParameter("nosign", "1")
            .addQueryParameter("disstid", id)
            .addQueryParameter("g_tk", "5381")
            .addQueryParameter("loginUin", "0")
            .addQueryParameter("hostUin", "0")
            .addQueryParameter("format", "json")
            .addQueryParameter("inCharset", "GB2312")
            .addQueryParameter("outCharset", "utf-8")
            .addQueryParameter("notice", "0")
            .addQueryParameter("platform", "yqq")
            .addQueryParameter("needNewCode", "0")
            .build()
        val raw = client.newCall(
            Request.Builder().url(requestUrl).header("Referer", "https://y.qq.com/").header("User-Agent", "Mozilla/5.0").get().build(),
        ).execute().use { response ->
            require(response.isSuccessful) { "QQ 歌单导入请求失败：HTTP ${response.code}" }
            response.body?.string().orEmpty().trim()
        }
        val normalized = if (raw.startsWith("callback(")) raw.removePrefix("callback(").removeSuffix(")") else raw
        val source = JSONObject(normalized).optJSONArray("cdlist")?.optJSONObject(0)
            ?: error("未读取到公开 QQ 歌单，请确认分享链接或歌单 ID")
        val tracks = buildList {
            val songs = source.optJSONArray("songlist") ?: JSONArray()
            for (index in 0 until songs.length()) {
                val song = songs.optJSONObject(index) ?: continue
                val mid = song.optString("songmid").ifBlank { song.optString("mid") }
                if (mid.isBlank()) continue
                val singers = song.optJSONArray("singer")
                val artist = buildList {
                    for (singerIndex in 0 until (singers?.length() ?: 0)) singers?.optJSONObject(singerIndex)?.optString("name")?.takeIf(String::isNotBlank)?.let(::add)
                }.joinToString(" / ").ifBlank { "未知歌手" }
                val album = song.optJSONObject("album")
                val albumMid = album?.optString("mid").orEmpty()
                val cover = albumMid.takeIf(String::isNotBlank)?.let { Uri.parse("https://y.gtimg.cn/music/photo_new/T002R300x300M000$it.jpg") }
                add(
                    Track(
                        id = "qq:$mid",
                        title = song.optString("songname").ifBlank { song.optString("name").ifBlank { "未知歌曲" } },
                        artist = artist,
                        album = album?.optString("name").orEmpty(),
                        durationMs = song.optLong("interval").coerceAtLeast(0L) * 1_000L,
                        artworkUri = cover,
                        source = Track.Source.QQ,
                        qqMid = mid,
                    ),
                )
            }
        }
        PlaylistDetail(
            summary = PlaylistSummary(
                id = "qq:$id",
                name = source.optString("dissname").ifBlank { "QQ 歌单" },
                coverUri = source.optString("logo").takeIf(String::isNotBlank)?.let(Uri::parse),
                trackCount = source.optInt("total_song_num", tracks.size),
                creator = source.optString("nickname").ifBlank { "QQ 音乐" },
                source = Track.Source.QQ,
            ),
            tracks = tracks,
        )
    }

    private fun requestChksz(settings: AppSettings, parameters: Map<String, String>): JSONObject {
        require(settings.chkszApiKey.isNotBlank()) { "请先在设置中填写 ChKSz API Key 后使用 QQ 音乐" }
        val base = (if (settings.useChkszBackup) "https://api.chksz.top" else settings.chkszBaseUrl).trimEnd('/').toHttpUrl()
        val url = base.newBuilder().addPathSegments("api/qq_music").apply {
            parameters.forEach { (key, value) -> addQueryParameter(key, value) }
            addQueryParameter("apikey", settings.chkszApiKey)
        }.build()
        client.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
            require(response.isSuccessful) { "QQ API 请求失败：HTTP ${response.code}" }
            val payload = JSONObject(response.body?.string().orEmpty())
            require(payload.optInt("code", 200) == 200) { payload.optString("msg").ifBlank { "QQ API 返回错误" } }
            return payload
        }
    }

    private fun extractPlaylistId(input: String): String {
        val matched = Regex("(?:disstid=|playlist/|taoge/)([0-9]{5,})", RegexOption.IGNORE_CASE).find(input)?.groupValues?.getOrNull(1)
            ?: Regex("[0-9]{5,}").find(input)?.value
        require(!matched.isNullOrBlank()) { "请输入 QQ 歌单分享链接或纯数字歌单 ID" }
        return matched
    }
}
