package im.molan.music.data.lyrics

import im.molan.music.data.db.LyricDao
import im.molan.music.data.db.LyricEntity
import im.molan.music.data.network.NcmRepository
import im.molan.music.model.AppSettings
import im.molan.music.model.LyricLine
import im.molan.music.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 歌词持久化到 Room，按曲目标识（source:id）入库引用；线上刷新后即落库，离线可查。
 */
class LyricsRepository(
    private val ncm: NcmRepository,
    private val dao: LyricDao,
) {
    /** 读取本地持久化歌词；未命中返回 null 以便调用方触发同步。 */
    suspend fun cached(track: Track): List<LyricLine>? = withContext(Dispatchers.IO) {
        dao.byKey(track.id)?.let { entity ->
            if (entity.missing) emptyList() else LrcParser.parse(entity.lyric, entity.translation)
        }
    }

    /** 从线上刷新歌词并写回数据库；本地曲目会先按歌名、歌手和时长匹配网易云。 */
    suspend fun refresh(settings: AppSettings, track: Track): List<LyricLine> = withContext(Dispatchers.IO) {
        val resolved = if (track.source == Track.Source.NETEASE) track else matchLocal(settings, track)
        if (resolved == null) {
            persist(track, "", "", missing = true)
            return@withContext emptyList()
        }
        runCatching { ncm.lyric(settings, resolved) }
            .fold(
                onSuccess = { (lrc, translated) ->
                    val parsed = LrcParser.parse(lrc, translated)
                    persist(track, lrc, translated, missing = parsed.isEmpty())
                    parsed
                },
                onFailure = { emptyList() },
            )
    }

    /** 兼容现有调用：没有本地缓存时才访问线上。 */
    suspend fun load(settings: AppSettings, track: Track): List<LyricLine> = cached(track) ?: refresh(settings, track)

    /** QQ 解析等已获得歌词的场景直接写入数据库。 */
    suspend fun save(track: Track, lrc: String, translation: String = "") = withContext(Dispatchers.IO) {
        persist(track, lrc, translation, missing = LrcParser.parse(lrc, translation).isEmpty())
    }

    private suspend fun persist(track: Track, lrc: String, translation: String, missing: Boolean) {
        dao.upsert(
            LyricEntity(
                trackKey = track.id,
                source = track.source.name,
                lyric = lrc,
                translation = translation,
                missing = missing,
                updatedAt = System.currentTimeMillis(),
            ),
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
}