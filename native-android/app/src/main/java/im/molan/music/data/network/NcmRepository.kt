package im.molan.music.data.network

import android.net.Uri
import im.molan.music.model.AppSettings
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

    suspend fun search(settings: AppSettings, keyword: String, limit: Int = 30): List<Track> = withContext(Dispatchers.IO) {
        val payload = request(settings, "/cloudsearch", mapOf("keywords" to keyword, "limit" to limit.toString()))
        val data = payload.optJSONObject("data") ?: payload.optJSONObject("result") ?: payload
        songs(data.optJSONArray("songs") ?: payload.optJSONArray("songs") ?: JSONArray())
    }

    suspend fun resolvePlayback(settings: AppSettings, track: Track): Track = withContext(Dispatchers.IO) {
        require(track.source == Track.Source.NETEASE) { "该曲目不是网易云音源" }
        val id = track.id.removePrefix("ncm:")
        val payload = request(settings, "/song/url/v1", mapOf("id" to id, "level" to settings.quality.wireValue))
        val rows = payload.optJSONArray("data") ?: JSONArray()
        val url = rows.optJSONObject(0)?.optString("url").orEmpty()
        require(url.isNotBlank()) { "当前音质没有可播放地址" }
        track.copy(remoteUrl = url)
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

    private fun request(settings: AppSettings, path: String, parameters: Map<String, String>, acceptedCodes: Set<Int> = setOf(200)): JSONObject {
        val base = settings.ncmcBaseUrl.trimEnd('/').toHttpUrl()
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
