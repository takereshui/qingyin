package im.molan.music.data.lyrics

import android.content.Context
import im.molan.music.data.network.NcmRepository
import im.molan.music.model.AppSettings
import im.molan.music.model.LyricLine
import im.molan.music.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

class LyricsRepository(private val context: Context, private val ncm: NcmRepository) {
    private val cacheDir = File(context.filesDir, "lyrics-v2").apply { mkdirs() }
    private val lyricsTtlMs = 90L * 24 * 60 * 60 * 1000
    private val missingTtlMs = 7L * 24 * 60 * 60 * 1000

    suspend fun load(settings: AppSettings, track: Track): List<LyricLine> = withContext(Dispatchers.IO) {
        readCache(track.id)?.let { cached ->
            if (cached.valid) return@withContext LrcParser.parse(cached.lrc, cached.translation)
            if (cached.missing) return@withContext emptyList()
        }
        val resolved = if (track.source == Track.Source.NETEASE) track else matchLocal(settings, track)
        if (resolved == null) {
            writeCache(track.id, "", "", missing = true)
            return@withContext emptyList()
        }
        runCatching { ncm.lyric(settings, resolved) }
            .fold(
                onSuccess = { (lrc, translated) ->
                    val parsed = LrcParser.parse(lrc, translated)
                    writeCache(track.id, lrc, translated, missing = parsed.isEmpty())
                    parsed
                },
                onFailure = { emptyList() },
            )
    }

    private suspend fun matchLocal(settings: AppSettings, local: Track): Track? {
        val candidates = ncm.search(settings, "${local.title} ${local.artist}", 20)
        return candidates.maxByOrNull { candidate -> score(local, candidate) }?.takeIf { score(local, it) >= 66 }
    }

    private fun score(local: Track, remote: Track): Int {
        val localTitle = normalize(local.title)
        val remoteTitle = normalize(remote.title)
        var score = when {
            localTitle == remoteTitle -> 62
            localTitle.contains(remoteTitle) || remoteTitle.contains(localTitle) -> 48
            else -> 0
        }
        val localArtists = tokenize(local.artist)
        val remoteArtists = tokenize(remote.artist)
        val common = localArtists.intersect(remoteArtists).size
        if (common > 0) score += minOf(24, common * 12)
        if (local.durationMs > 0 && remote.durationMs > 0) {
            val delta = kotlin.math.abs(local.durationMs - remote.durationMs)
            score += when {
                delta <= 2_500 -> 12
                delta <= 8_000 -> 6
                else -> 0
            }
        }
        return score
    }

    private fun normalize(value: String) = value.lowercase()
        .replace(Regex("[（(].*?[）)]"), "")
        .replace(Regex("[^a-z0-9\\p{IsHan}]"), "")

    private fun tokenize(value: String = "") = normalize(value).split(Regex("[\\s/、,，&]+"))
        .filter { it.length >= 2 }.toSet()

    private data class Cached(val valid: Boolean, val missing: Boolean, val lrc: String, val translation: String)

    private fun readCache(trackId: String): Cached? = runCatching {
        val json = JSONObject(cacheFile(trackId).readText())
        val created = json.optLong("createdAt")
        val missing = json.optBoolean("missing")
        val age = System.currentTimeMillis() - created
        val active = age in 0..if (missing) missingTtlMs else lyricsTtlMs
        Cached(active && !missing, active && missing, json.optString("lrc"), json.optString("translation"))
    }.getOrNull()

    private fun writeCache(trackId: String, lrc: String, translation: String, missing: Boolean) {
        val json = JSONObject()
            .put("createdAt", System.currentTimeMillis())
            .put("missing", missing)
            .put("lrc", lrc)
            .put("translation", translation)
        cacheFile(trackId).writeText(json.toString())
    }

    private fun cacheFile(trackId: String): File = File(cacheDir, sha256(trackId) + ".json")

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}
