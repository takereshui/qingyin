package im.molan.music.data.network

import android.net.Uri
import im.molan.music.model.AppSettings
import im.molan.music.model.PlaylistDetail
import im.molan.music.model.PlaylistSummary
import im.molan.music.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyFactory
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random
import java.util.concurrent.TimeUnit

data class NcmQrCheck(val code: Int, val cookie: String)

data class NcmAccount(val nickname: String, val userId: Long)

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
        NcmAccount(profile.optString("nickname"), profile.optLong("userId", 0L))
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
        val payload = officialWeapi(settings, "/weapi/cloudsearch/pc", mapOf(
            "s" to keyword,
            "type" to 1,
            "limit" to limit,
            "offset" to 0,
            "total" to true,
        ))
        val data = payload.optJSONObject("result") ?: payload.optJSONObject("data") ?: payload
        songs(data.optJSONArray("songs") ?: payload.optJSONArray("songs") ?: JSONArray())
    }

    /** Q-music 采用的网易云官方播放地址：登录会话 + WeAPI，URL 只短期有效。 */
    suspend fun resolveOfficial(settings: AppSettings, track: Track, isDownload: Boolean = false): Track = withContext(Dispatchers.IO) {
        require(track.source == Track.Source.NETEASE) { "该曲目不是网易云音源" }
        require(settings.ncmCookie.isNotBlank()) { "请先登录网易云官方账号" }
        val target = if (isDownload) settings.quality else settings.streamQuality
        val order = listOf(AppSettings.Quality.HIRES, AppSettings.Quality.LOSSLESS, AppSettings.Quality.EXHIGH, AppSettings.Quality.HIGH, AppSettings.Quality.STANDARD)
        val candidates = (listOf(target) + order).distinct()
        val id = track.id.removePrefix("ncm:").toLongOrNull() ?: error("网易云歌曲 ID 无效")
        for (candidate in candidates) {
            val payload = runCatching {
                officialWeapi(settings, "/weapi/song/enhance/player/url/v1", mapOf(
                    "ids" to "[$id]",
                    "level" to candidate.neteaseLevel(),
                    "encodeType" to "flac",
                ))
            }.getOrNull() ?: continue
            val item = payload.optJSONArray("data")?.optJSONObject(0) ?: continue
            val rawUrl = item.optString("url").replace("\\/", "/")
            if (payload.optInt("code", 0) == 200 && item.optInt("code", 200) == 200 && rawUrl.startsWith("http")) {
                return@withContext track.copy(
                    remoteUrl = rawUrl,
                    resolvedQuality = candidate,
                    audioExtension = audioExtensionFromUrl(rawUrl),
                    resolvedAt = System.currentTimeMillis(),
                )
            }
        }
        error("网易云官方音源不可用，可能需要会员或受版权限制")
    }

    /**
     * 网易云音源只通过用户配置的 ChKSz 私有 API 解析。
     * NCMC 仅保留搜索、歌单、歌词与登录等元数据能力，绝不再调用其音源端点。
     * 下载只请求所选音质；在线播放可以由私有 API 依次尝试更低的可用档位。
     */
    suspend fun resolveRemote(settings: AppSettings, track: Track, isDownload: Boolean = false): Track = withContext(Dispatchers.IO) {
        require(track.source == Track.Source.NETEASE) { "该曲目不是网易云音源" }
        require(settings.chkszApiKey.isNotBlank()) { "请先在设置中填写 ChKSz API Key 后再播放或下载网易云音乐" }

        val target = if (isDownload) settings.quality else settings.streamQuality
        val id = track.id.removePrefix("ncm:")
        val privateUrl = chkszFallbackUrl(settings, id, target, allowDowngrade = !isDownload)
            ?: error(
                if (isDownload) {
                    "ChKSz 私有 API 未提供“${target.label}”下载地址；请调整下载音质或检查 API 配置"
                } else {
                    "ChKSz 私有 API 未提供可播放地址；请检查 API 配置或试听音质"
                },
            )

        // 不复用旧 remoteUrl，防止歌单缓存或历史队列继续使用此前由 NCMC 解析出的音源地址。
        track.copy(
            remoteUrl = privateUrl,
            resolvedQuality = null,
            // URL 可能是无扩展名的 API 路由；不能直接对完整 URL substringAfterLast('.')，
            // 否则会把域名的 .com/... 误当作音频格式并生成无法写标签的下载文件名。
            audioExtension = audioExtensionFromUrl(privateUrl),
            resolvedAt = System.currentTimeMillis(),
        )
    }

    /** ChKSz 是主线路；仅当 ChKSz 不可用时才尝试网易云官方短时地址。 */
    suspend fun resolvePlayback(settings: AppSettings, track: Track): Track = runCatching { resolveRemote(settings, track, isDownload = false) }
        .getOrElse { resolveOfficial(settings, track, isDownload = false) }
    suspend fun resolveDownload(settings: AppSettings, track: Track): Track = runCatching { resolveRemote(settings, track, isDownload = true) }
        .getOrElse { resolveOfficial(settings, track, isDownload = true) }


    private fun AppSettings.Quality.neteaseLevel(): String = when (this) {
        AppSettings.Quality.HIRES, AppSettings.Quality.JYMASTER -> "hires"
        AppSettings.Quality.LOSSLESS -> "lossless"
        AppSettings.Quality.EXHIGH, AppSettings.Quality.HIGH -> "exhigh"
        AppSettings.Quality.STANDARD -> "standard"
    }

    private fun officialWeapi(settings: AppSettings, path: String, values: Map<String, Any>): JSONObject {
        val csrf = Regex("(?:^|; )__csrf=([^;]*)").find(settings.ncmCookie)?.groupValues?.getOrNull(1).orEmpty()
        val data = JSONObject().apply {
            values.forEach { (key, value) -> put(key, value) }
            put("csrf_token", csrf)
        }
        val (params, encSecKey) = weapiForm(data.toString())
        val body = FormBody.Builder().add("params", params).add("encSecKey", encSecKey).build()
        val url = "https://music.163.com$path".toHttpUrl()
        client.newCall(Request.Builder().url(url).post(body)
            .header("Cookie", settings.ncmCookie)
            .header("Origin", "https://music.163.com")
            .header("Referer", "https://music.163.com/")
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36")
            .build()).execute().use { response ->
            if (!response.isSuccessful) error("网易云官方接口 HTTP ${response.code}")
            val raw = response.body?.string().orEmpty()
            require(raw.isNotBlank()) { "网易云官方接口返回空响应" }
            return JSONObject(raw)
        }
    }

    private fun weapiForm(json: String): Pair<String, String> {
        val iv = "0102030405060708".toByteArray(Charsets.UTF_8)
        val preset = "0CoJUm6Qyw8W8jud".toByteArray(Charsets.UTF_8)
        val alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        val secret = ByteArray(16) { alphabet[Random.nextInt(alphabet.length)].code.toByte() }
        val first = aesCbc(json.toByteArray(Charsets.UTF_8), preset, iv)
        val params = aesCbc(first.encodeBase64().toByteArray(Charsets.UTF_8), secret, iv).encodeBase64()
        val reversed = secret.reversedArray()
        val padded = ByteArray(128 - reversed.size) + reversed
        val publicKey = "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDgtQn2JZ34ZC28NWYpAUd98iZ37BUrX/aKzmFbt7clFSs6sXqHauqKWqdtLkF2KexO40H1YTX8z2lSgBBOAxLsvaklV8k4cBFK9snQXE9/DDaFt6Rr7iVZMldczhC0JNgTz+SHXT6CBHuX3e9SdB1Ua44oncaTWz7OBGLbCiK45wIDAQAB"
        val key = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(android.util.Base64.decode(publicKey, android.util.Base64.DEFAULT)))
        val cipher = Cipher.getInstance("RSA/ECB/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
        val encrypted = cipher.doFinal(padded).toHex()
        return params to encrypted
    }

    private fun aesCbc(input: ByteArray, key: ByteArray, iv: ByteArray): ByteArray = Cipher.getInstance("AES/CBC/PKCS5Padding").run {
        init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        doFinal(input)
    }

    private fun ByteArray.encodeBase64(): String = android.util.Base64.encodeToString(this, android.util.Base64.NO_WRAP)
    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun audioExtensionFromUrl(url: String): String {
        val filePart = url.substringBefore('?').substringAfterLast('/')
        val suffix = filePart.substringAfterLast('.', "").lowercase()
        return suffix.takeIf { it in SUPPORTED_AUDIO_EXTENSIONS } ?: "bin"
    }

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

    /** ChKSz 兜底：仅使用用户配置的私有主线路，必须携带 API Key；不再内置官方 top 域名备选。 */
    private fun chkszFallbackUrl(
        settings: AppSettings,
        id: String,
        target: AppSettings.Quality,
        allowDowngrade: Boolean,
    ): String? {
        if (settings.chkszApiKey.isBlank()) return null
        val base = settings.chkszBaseUrl.trim().ifBlank { return null }.trimEnd('/').removeSuffix("/api").toHttpUrl()
        // ChKSz 不认 higher，映射为 exhigh；自目标起降级
        val targetLevel = target.wireValue.let { if (it == "higher") "exhigh" else it }
        val levelOrder = listOf("jymaster", "hires", "lossless", "exhigh", "standard")
        val levels = if (allowDowngrade) {
            (listOf(targetLevel) + levelOrder.dropWhile { it != targetLevel }.drop(1) + listOf("exhigh", "standard")).distinct()
        } else {
            listOf(targetLevel)
        }
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

    private val SUPPORTED_AUDIO_EXTENSIONS = setOf("mp3", "flac", "m4a", "aac", "ogg", "opus", "wav", "mp4")

    private fun JSONArray?.names(): String = buildList {
        if (this@names == null) return@buildList
        for (index in 0 until length()) {
            val person = optJSONObject(index)
            person?.optString("name")?.takeIf(String::isNotBlank)?.let(::add)
        }
    }.joinToString(" / ")
}
