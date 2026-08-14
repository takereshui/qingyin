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
        val tracks = buildList {
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
        // ChKSz 搜索结果不返回专辑 MID；一次 QQ 官方搜索请求补齐整页封面，避免逐首网络等待。
        val covers = qqCovers(keyword, tracks.size)
        tracks.map { track -> track.copy(artworkUri = covers[track.qqMid]) }
    }

    /**
     * 统一 QQ 解析入口。
     * @param isDownload 为 true 时，若已有地址音质不符，则重新请求。
     */
    suspend fun resolve(settings: AppSettings, track: Track, isDownload: Boolean = false): ResolvedQqTrack = withContext(Dispatchers.IO) {
        require(track.source == Track.Source.QQ) { "该曲目不是 QQ 音源" }

        val target = if (isDownload) settings.qqQuality else settings.streamQqQuality
        // CDN 约 20min 过期，保守 15min 复用上限
        val now = System.currentTimeMillis()
        val ageMs = now - track.resolvedAt
        val isExpired = ageMs > REMOTE_URL_TTL_MS
        val url = track.remoteUrl
        val hasValidUrl = !url.isNullOrBlank() && (url.startsWith("https://") || url.startsWith("http://"))

        if (hasValidUrl && !isExpired) {
            // 下载不能复用音质未知的试听地址，否则会把试听音质误当成下载音质。
            val qualityMatches = track.resolvedQqQuality == target || (!isDownload && track.resolvedQqQuality == null)
            if (qualityMatches) {
                return@withContext ResolvedQqTrack(track, "") // 复用时不需要重新返回歌词
            }
        }

        val mid = track.qqMid.orEmpty().ifBlank { track.id.removePrefix("qq:") }
        require(mid.isNotBlank()) { "QQ 曲目缺少 MID" }

        // 播放与下载一致：先请求目标音质；失败则按 母带→Hi-Res→FLAC→320→128 降级，
        // 避免“所选音质不可用”时在线播放直接失败。
        val sizes = if (isDownload) {
            listOf(target.wireValue)
        } else {
            listOf(target.wireValue) + listOf("master", "hires", "flac", "320k", "128k").filterNot { it == target.wireValue }
        }
        var lastFailure = "QQ 未提供 ${target.label} 音源"
        for (size in sizes) {
            val payload = runCatching {
                requestChksz(settings, mapOf("mid" to mid, "size" to size, "type" to "json"))
            }.getOrNull() ?: continue
            val rawUrl = payload.optString("url").replace("\\/", "/")
            if (rawUrl.isBlank()) continue

            val actual = payload.qQQuality()
            val format = payload.optString("format").lowercase().takeIf(String::isNotBlank)
                ?: rawUrl.substringBefore('?').substringAfterLast('.', "mp3")
            val cover = payload.optString("cover").takeIf(String::isNotBlank)?.let(Uri::parse) ?: track.artworkUri

            return@withContext ResolvedQqTrack(
                track = track.copy(
                    title = payload.optString("name").ifBlank { track.title },
                    artist = payload.optString("singer").ifBlank { track.artist },
                    album = payload.optString("album").ifBlank { track.album },
                    artworkUri = cover,
                    remoteUrl = rawUrl,
                    resolvedQqQuality = actual,
                    audioExtension = format,
                    qqMid = mid,
                    resolvedAt = System.currentTimeMillis(),
                ),
                lyric = payload.optString("lrc"),
            )
        }
        error(lastFailure)
    }

    suspend fun resolveDownload(settings: AppSettings, track: Track): Track = resolve(settings, track, isDownload = true).track

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

    private fun qqCovers(keyword: String, limit: Int): Map<String, Uri> = runCatching {
        val url = "https://c.y.qq.com/soso/fcgi-bin/client_search_cp".toHttpUrl().newBuilder()
            .addQueryParameter("p", "1")
            .addQueryParameter("n", limit.coerceIn(1, 50).toString())
            .addQueryParameter("w", keyword)
            .addQueryParameter("format", "json")
            .build()
        client.newCall(Request.Builder().url(url).header("Referer", "https://y.qq.com/").header("User-Agent", "Mozilla/5.0").get().build()).execute().use { response ->
            if (!response.isSuccessful) return@use emptyMap()
            val rows = JSONObject(response.body?.string().orEmpty())
                .optJSONObject("data")?.optJSONObject("song")?.optJSONArray("list") ?: JSONArray()
            buildMap {
                for (index in 0 until rows.length()) {
                    val row = rows.optJSONObject(index) ?: continue
                    val songMid = row.optString("songmid")
                    val albumMid = row.optString("albummid")
                    if (songMid.isNotBlank() && albumMid.isNotBlank()) {
                        put(songMid, Uri.parse("https://y.gtimg.cn/music/photo_new/T002R300x300M000$albumMid.jpg"))
                    }
                }
            }
        }
    }.getOrDefault(emptyMap())

    private fun requestChksz(settings: AppSettings, parameters: Map<String, String>): JSONObject {
        require(settings.chkszApiKey.isNotBlank()) { "请先在设置中填写 ChKSz API Key 后使用 QQ 音乐" }
        if (settings.chkszBaseUrl.isBlank()) error("ChKSz 主线路地址为空，请在设置中填写")
        // 只走用户配置的私有主线路（必须用 Key）；不再内置官方 top 域名备选。
        val url = normalizeApiBase(settings.chkszBaseUrl).newBuilder().addPathSegments("api/qq_music").apply {
            parameters.forEach { (key, value) -> addQueryParameter(key, value) }
            addQueryParameter("apikey", settings.chkszApiKey)
        }.build()
        client.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (response.code == 404) error("QQ API 路由不存在：${response.request.url.host}")
            if (!response.isSuccessful) error("QQ API 请求失败：HTTP ${response.code}")
            val payload = runCatching { JSONObject(raw) }.getOrElse { error("QQ API 返回了无法识别的数据") }
            if (payload.optInt("code", 200) != 200) error(payload.optString("msg").ifBlank { "QQ API 返回错误" })
            return payload
        }
    }

    /** 兼容用户把主地址误填成 https://api.chksz.com/api 的旧配置。 */
    private fun normalizeApiBase(raw: String) = raw.trim().ifBlank { "https://api.chksz.com" }
        .trimEnd('/')
        .removeSuffix("/api")
        .toHttpUrl()

    private fun JSONObject.qQQuality(): AppSettings.QqQuality? {
        val value = optString("bitrate").ifBlank { optString("size") }.trim().lowercase()
        return when (value) {
            "128", "128k", "128000" -> AppSettings.QqQuality.K128
            "320", "320k", "320000" -> AppSettings.QqQuality.K320
            "flac", "lossless" -> AppSettings.QqQuality.FLAC
            "hires", "hi-res", "hi_res" -> AppSettings.QqQuality.HIRES
            "master", "jymaster" -> AppSettings.QqQuality.MASTER
            else -> null
        }
    }

    private companion object {
        /** CDN 约 20min 过期，保守 15min 复用上限 */
        const val REMOTE_URL_TTL_MS = 15L * 60 * 1000
    }

    private fun extractPlaylistId(input: String): String {
        val matched = Regex("(?:disstid=|playlist/|taoge/)([0-9]{5,})", RegexOption.IGNORE_CASE).find(input)?.groupValues?.getOrNull(1)
            ?: Regex("[0-9]{5,}").find(input)?.value
        require(!matched.isNullOrBlank()) { "请输入 QQ 歌单分享链接或纯数字歌单 ID" }
        return matched
    }
}
