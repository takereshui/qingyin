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

data class NcmQrCheck(val code: Int, val cookie: String)

data class NcmAccount(val nickname: String, val userId: Long)

private const val OFFICIAL_CHKSZ_BASE = "https://api.chksz.com"
/** CDN 约 20min 过期，保守 15min 复用上限 */
private const val REMOTE_URL_TTL_MS = 15L * 60 * 1000

class NcmRepository(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(18, TimeUnit.SECONDS)
        .build(),
) {
    suspend fun qrKey(settings: AppSettings): String = withContext(Dispatchers.IO) {
        val payload = request(settings, "/login/qr/key", emptyMap())
        payload.optJSONObject("data")?.optString("unikey").orEmpty().ifBlank { payload.optString("unikey") }
            .also { require(it.isNotBlank()) { "未返回二维码密钥" } }
    }

    suspend fun qrImage(settings: AppSettings, key: String): String = withContext(Dispatchers.IO) {
        val payload = request(settings, "/login/qr/create", mapOf("key" to key, "qrimg" to "true"))
        payload.optJSONObject("data")?.optString("qrimg").orEmpty().ifBlank { payload.optString("qrimg") }
            .also { require(it.isNotBlank()) { "未返回二维码图片" } }
    }

    suspend fun qrCheck(settings: AppSettings, key: String): NcmQrCheck = withContext(Dispatchers.IO) {
        val payload = request(settings, "/login/qr/check", mapOf("key" to key), setOf(800, 801, 802, 803))
        NcmQrCheck(payload.optInt("code", 801), payload.optString("cookie"))
    }

    suspend fun account(settings: AppSettings): NcmAccount? = withContext(Dispatchers.IO) {
        val payload = request(settings, "/user/account", emptyMap())
        val profile = payload.optJSONObject("profile") ?: payload.optJSONObject("data")?.optJSONObject("profile") ?: return@withContext null
        NcmAccount(profile.optString("nickname"), profile.optLong("userId", profile.optLong("userId")))
    }

    suspend fun userPlaylists(settings: AppSettings, userId: Long, limit: Int = 60): List<PlaylistSummary> = withContext(Dispatchers.IO) {
        require(userId > 0) { "未获取到网易云用户信息" }
        val payload = request(settings, "/user/playlist", mapOf("uid" to userId.toString(), "limit" to limit.toString()))
        val rows = payload.optJSONArray("playlist") ?: payload.optJSONObject("data")?.optJSONArray("playlist") ?: JSONArray()
        playlistSummaries(rows)
    }

    suspend fun playlistDetail(settings: AppSettings, playlistId: String): PlaylistDetail = withContext(Dispatchers.IO) {
        val payload = request(settings, "/playlist/detail", mapOf("id" to playlistId))
        val data = payload.optJSONObject("playlist") ?: payload.optJSONObject("data") ?: payload
        val summary = playlistSummary(data, playlistId)
        val tracks = songs(data.optJSONArray("tracks") ?: data.optJSONArray("songs") ?: JSONArray())
        PlaylistDetail(summary, tracks)
    }

    suspend fun dailySongs(settings: AppSettings): List<Track> = withContext(Dispatchers.IO) {
        val payload = request(settings, "/recommend/songs", emptyMap())
        val data = payload.optJSONObject("data") ?: payload
        songs(data.optJSONArray("dailySongs") ?: payload.optJSONArray("dailySongs") ?: JSONArray())
    }

    suspend fun search(settings: AppSettings, keyword: String, limit: Int = 30): List<Track> = withContext(Dispatchers.IO) {
        val payload = request(settings, "/cloudsearch", mapOf("keywords" to keyword, "limit" to limit.toString()))
        val data = payload.optJSONObject("data") ?: payload.optJSONObject("result") ?: payload
        songs(data.optJSONArray("songs") ?: payload.optJSONArray("songs") ?: JSONArray())
    }

    /**
     * 统一解析入口：合并播放与下载路径。
     * @param isDownload 为 true 时，复用要求音质一致；降级后的真实音质写回 resolvedQuality。
     */
    suspend fun resolveRemote(settings: AppSettings, track: Track, isDownload: Boolean = false): Track = withContext(Dispatchers.IO) {
        require(track.source == Track.Source.NETEASE) { "该曲目不是网易云音源" }

        val now = System.currentTimeMillis()
        val ageMs = now - track.resolvedAt
        val isExpired = ageMs > REMOTE_URL_TTL_MS
        val url = track.remoteUrl
        val hasValidUrl = !url.isNullOrBlank() && (url.startsWith("https://") || url.startsWith("http://"))

        if (hasValidUrl && !isExpired) {
            if (!isDownload || track.resolvedQuality == settings.quality) return@withContext track
        }

        val id = track.id.removePrefix("ncm:")
        val hasCookie = settings.ncmCookie.isNotBlank()
        val hasChksz = settings.chkszApiKey.isNotBlank()

        if (!hasCookie) {
            chkszFallbackUrl(settings, id)?.let { fallbackUrl ->
                return@withContext track.copy(
                    remoteUrl = fallbackUrl,
                    resolvedQuality = null,
                    audioExtension = fallbackUrl.substringBefore('?').substringAfterLast('.', "mp3"),
                    resolvedAt = System.currentTimeMillis(),
                )
            }
            error(
                if (hasChksz) "网易云未登录且 ChKSz 未返回可用地址，请先登录网易云账号"
                else "请先登录网易云账号后再播放/下载",
            )
        }

        val qualityOrder = listOf(
            AppSettings.Quality.JYMASTER,
            AppSettings.Quality.HIRES,
            AppSettings.Quality.LOSSLESS,
            AppSettings.Quality.EXHIGH,
            AppSettings.Quality.HIGH,
            AppSettings.Quality.STANDARD,
        )
        val levels = qualityOrder.dropWhile { it != settings.quality }

        var lastError = "网易云未返回可用播放地址（cookie 可能过期或无该音质权益）"
        for (requested in levels) {
            val payload = try {
                request(settings, "/song/url/v1", mapOf("id" to id, "level" to requested.wireValue))
            } catch (e: Exception) {
                lastError = e.message?.takeIf { it.isNotBlank() } ?: lastError
                continue
            }
            val row = (payload.optJSONArray("data") ?: JSONArray()).optJSONObject(0) ?: continue
            val rawUrl = row.optString("url").replace("\\/", "/")
            if (rawUrl.isBlank()) continue

            val actual = AppSettings.Quality.entries.firstOrNull { it.wireValue == row.optString("level") }
            val extension = row.optString("type").lowercase().takeIf(String::isNotBlank)

            return@withContext track.copy(
                remoteUrl = rawUrl,
                resolvedQuality = actual,
                audioExtension = extension,
                resolvedAt = System.currentTimeMillis(),
            )
        }

        chkszFallbackUrl(settings, id)?.let { fallbackUrl ->
            return@withContext track.copy(
                remoteUrl = fallbackUrl,
                resolvedQuality = null,
                audioExtension = fallbackUrl.substringBefore('?').substringAfterLast('.', "mp3"),
                resolvedAt = System.currentTimeMillis(),
            )
        }

        error(
            when {
                isDownload && !hasChksz ->
                    "网易云未提供“${settings.quality.label}”下载地址；可调低音质、检查登录，或配置 ChKSz 兜底"
                !hasChksz ->
                    "$lastError；未配置 ChKSz 兜底"
                else ->
                    if (isDownload) "网易云与 ChKSz 均未提供“${settings.quality.label}”可用地址"
                    else "$lastError；ChKSz 兜底也失败"
            },
        )
    }

    suspend fun resolvePlayback(settings: AppSettings, track: Track): Track = resolveRemote(settings, track, isDownload = false)
    suspend fun resolveDownload(settings: AppSettings, track: Track): Track = resolveRemote(settings, track, isDownload = true)

    suspend fun lyric(settings: AppSettings, track: Track): Pair<String, String> = withContext(Dispatchers.IO) {
        val id = track.id.removePrefix("ncm:")
        val payload = request(settings, "/lyric", mapOf("id" to id))
        val data = payload.optJSONObject("data") ?: payload
        val lrc = data.optJSONObject("lrc")?.optString("lyric")
            ?: payload.optJSONObject("lrc")?.optString("lyric")
            ?: data.optString("lyric")
        val translated = data.optJSONObject("tlyric")?.optString("lyric")
            ?: payload.optJSONObject("tlyric")?.optString("lyric")
            ?: ""
        lrc.orEmpty() to translated.orEmpty()
    }

    private fun chkszFallbackUrl(settings: AppSettings, id: String): String? {
        if (settings.chkszApiKey.isBlank()) return null
        val bases = listOf(settings.chkszBaseUrl, OFFICIAL_CHKSZ_BASE)
            .map { it.trim().ifBlank { OFFICIAL_CHKSZ_BASE }.trimEnd('/').removeSuffix("/api").toHttpUrl() }
            .distinct()
        // ChKSz 不认 higher，映射为 exhigh；自目标起降级
        val targetLevel = settings.quality.wireValue.let { if (it == "higher") "exhigh" else it }
        val levelOrder = listOf("jymaster", "hires", "lossless", "exhigh", "standard")
        val levels = (listOf(targetLevel) + levelOrder.dropWhile { it != targetLevel }.drop(1) + listOf("exhigh", "standard"))
            .distinct()
        for (base in bases) {
            for (level in levels) {
                val url = base.newBuilder().addPathSegments("api/163_music")
                    .addQueryParameter("id", id)
                    .addQueryParameter("level", level)
                    .addQueryParameter("type", "json")
                    .addQueryParameter("apikey", settings.chkszApiKey)
                    .build()
                val playable = runCatching {
                    client.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
                        if (!response.isSuccessful) return@use null
                        val payload = JSONObject(response.body?.string().orEmpty())
                        val data = payload.optJSONObject("data") ?: payload
                        val raw = data.optString("url").ifBlank { payload.optString("url") }
                            .replace("\\/", "/")
                            .replaceFirst("http://", "https://")
                        raw.takeIf { it.startsWith("https://") }
                    }
                }.getOrNull()
                if (playable != null) return playable
            }
        }
        return null
    }

    private fun request(settings: AppSettings, path: String, parameters: Map<String, String>, acceptedCodes: Set<Int> = setOf(200)): JSONObject {
        val configuredBase = if (settings.useBackupNcmc && settings.backupNcmcBaseUrl.isNotBlank()) settings.backupNcmcBaseUrl else settings.ncmcBaseUrl
        val base = configuredBase.trimEnd('/').toHttpUrl()
        val builder = base.newBuilder().addPathSegments(path.removePrefix("/"))
        parameters.forEach { (key, value) -> builder.addQueryParameter(key, value) }
        if (settings.ncmCookie.isNotBlank()) builder.addQueryParameter("cookie", settings.ncmCookie)
        builder.addQueryParameter("timestamp", System.currentTimeMillis().toString())
        val response = client.newCall(Request.Builder().url(builder.build()).get().build()).execute()
        response.use {
            if (!it.isSuccessful) error("NCMC HTTP ${it.code}")
            val raw = it.body?.string().orEmpty()
            require(raw.isNotBlank()) { "NCMC 返回空响应" }
            val result = JSONObject(raw)
            val code = result.optInt("code", 200)
            if (code !in acceptedCodes) error(result.optString("msg").ifBlank { "NCMC 错误 $code" })
            return result
        }
    }

    private fun playlistSummaries(rows: JSONArray): List<PlaylistSummary> = buildList {
        for (index in 0 until rows.length()) {
            rows.optJSONObject(index)?.let { add(playlistSummary(it, "")) }
        }
    }

    private fun playlistSummary(row: JSONObject, fallbackId: String): PlaylistSummary {
        val id = row.optLong("id", fallbackId.toLongOrNull() ?: 0L).toString().ifBlank { fallbackId }
        val cover = row.optString("coverImgUrl").ifBlank { row.optString("picUrl") }
            .takeIf(String::isNotBlank)
            ?.let { value -> Uri.parse("${value.removePrefix("http:").let { clean -> if (clean.startsWith("//")) "https:$clean" else clean }}?param=300y300") }
        return PlaylistSummary(
            id = id,
            name = row.optString("name").ifBlank { "歌单" },
            coverUri = cover,
            trackCount = row.optInt("trackCount"),
            creator = row.optJSONObject("creator")?.optString("nickname").orEmpty(),
        )
    }

    private fun songs(rows: JSONArray): List<Track> = buildList {
        for (index in 0 until rows.length()) {
            val song = rows.optJSONObject(index) ?: continue
            val id = song.optLong("id", 0L)
            if (id <= 0L) continue
            val artists = song.optJSONArray("ar") ?: song.optJSONArray("artists")
            val artistText = artists.names().orEmpty()
            val album = song.optJSONObject("al") ?: song.optJSONObject("album")
            val cover = album?.optString("picUrl").orEmpty().takeIf(String::isNotBlank)?.let { Uri.parse("${it.removePrefix("http:").let { value -> if (value.startsWith("//")) "https:$value" else value }}?param=300y300") }
            add(
                Track(
                    id = "ncm:$id",
                    title = song.optString("name").ifBlank { "未知歌曲" },
                    artist = artistText.ifBlank { "未知歌手" },
                    album = album?.optString("name").orEmpty(),
                    durationMs = song.optLong("dt", song.optLong("duration", 0L)),
                    artworkUri = cover,
                    source = Track.Source.NETEASE,
                ),
            )
        }
    }

    private fun JSONArray?.names(): String = buildList {
        if (this@names == null) return@buildList
        for (index in 0 until length()) {
            val person = optJSONObject(index)
            person?.optString("name")?.takeIf(String::isNotBlank)?.let(::add)
        }
    }.joinToString(" / ")
}
