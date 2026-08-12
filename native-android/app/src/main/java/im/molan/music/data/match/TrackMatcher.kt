package im.molan.music.data.match

import im.molan.music.model.Track
import kotlin.math.abs
import kotlin.math.max

/**
 * 线上 / 本地统一歌曲匹配器。
 *
 * 设计参考 APlayer 的“规范键 + 时长门槛 + 标题/艺人/专辑加权评分”思路，但采用独立实现。
 * 本地音源必须达到阈值才会替代线上解析，宁可回退线上，也不误播相似歌曲。
 */
object TrackMatcher {
    const val ACCEPTANCE_SCORE = 55f
    private const val DURATION_TOLERANCE_MS = 4_000L

    data class Result(
        val local: Track,
        val score: Float,
        val titleScore: Float,
        val artistScore: Float?,
        val albumScore: Float?,
    )

    /** 供搜索、歌词和诊断使用的分层检索键，顺序由强到弱。 */
    /** 用于内存候选索引：精确标题、去版本标题、艺人标题和标题前缀。 */
    fun indexKeys(track: Track): Set<String> {
        val title = normalize(track.title)
        if (title.isBlank()) return emptySet()
        val titleWithoutTags = title.replace(Regex("\\s*[\\[(（].*?[\\])）]"), "").trim().ifBlank { title }
        val artist = normalize(track.artist)
        return buildSet {
            add("t:$title")
            add("t:$titleWithoutTags")
            if (artist.isNotBlank()) add("at:$artist|$title")
            if (title.length >= 4) add("p:${title.take(4)}")
        }
    }

    fun searchKeys(track: Track): List<String> {
        val title = track.title.takeIf(::isValidInfo)
        val artist = track.artist.takeIf(::isValidInfo)
        val album = track.album.takeIf(::isValidInfo)
        return buildList {
            if (artist != null && title != null) add("$artist - $title")
            if (title != null) add(title)
            if (artist == null && album != null && title != null) add("$album - $title")
        }.distinct()
    }

    fun findBest(target: Track, candidates: List<Track>): Result? = candidates
        .asSequence()
        .filter { it.uri != null && (it.source == Track.Source.LOCAL || it.source == Track.Source.DOWNLOADED) }
        .mapNotNull { candidate -> score(target, candidate) }
        .filter { it.score >= ACCEPTANCE_SCORE }
        .maxByOrNull { it.score }

    fun score(target: Track, local: Track): Result? {
        if (!durationCompatible(target.durationMs, local.durationMs)) return null
        val titleScore = textScore(target.title, local.title)
        val artistScore = optionalScore(target.artist, local.artist, ::artistScore)
        val albumScore = optionalScore(target.album, local.album, ::textScore)
        var finalScore = when {
            artistScore != null && albumScore != null -> titleScore * 0.50f + artistScore * 0.35f + albumScore * 0.15f
            artistScore != null -> titleScore * 0.60f + artistScore * 0.40f
            albumScore != null -> titleScore * 0.75f + albumScore * 0.25f
            else -> titleScore
        }
        // 标题相似度过低时，即便艺人或专辑相同也不允许误匹配。
        if (titleScore < 30f) finalScore = max(0f, finalScore - 35f)
        return Result(local, finalScore, titleScore, artistScore, albumScore)
    }

    private fun durationCompatible(a: Long, b: Long): Boolean =
        a <= 0L || b <= 0L || abs(a - b) <= DURATION_TOLERANCE_MS

    private fun optionalScore(a: String, b: String, scorer: (String, String) -> Float): Float? =
        if (!isValidInfo(a)) null else if (!isValidInfo(b)) 0f else scorer(a, b)

    private fun artistScore(a: String, b: String): Float {
        val left = splitArtists(a)
        val right = splitArtists(b)
        if (left.isEmpty() || right.isEmpty()) return 0f
        val pairs = left.indices.flatMap { i -> right.indices.map { j -> Triple(i, j, textScore(left[i], right[j])) } }
            .sortedByDescending { it.third }
        val usedLeft = mutableSetOf<Int>()
        val usedRight = mutableSetOf<Int>()
        var total = 0f
        pairs.forEach { (i, j, similarity) ->
            if (i !in usedLeft && j !in usedRight) {
                usedLeft += i
                usedRight += j
                total += similarity
            }
        }
        return total / max(left.size, right.size)
    }

    private fun textScore(a: String, b: String): Float {
        val left = normalize(a)
        val right = normalize(b)
        if (left.isBlank() || right.isBlank()) return 0f
        if (left == right) return 100f
        val base = similarity(left, right) * 100f
        val prefix = commonPrefix(left, right)
        if (prefix.length < 3 || prefix.length == left.length || prefix.length == right.length) return base
        val leftSuffix = left.removePrefix(prefix)
        val rightSuffix = right.removePrefix(prefix)
        val prefixWeight = prefix.length.toFloat() / ((leftSuffix.length + rightSuffix.length) / 2f + prefix.length)
        val suffixScore = similarity(leftSuffix, rightSuffix) * 100f
        val tags = tagScore(leftSuffix, rightSuffix)
        return max(base, (100f * prefixWeight + suffixScore * (1f - prefixWeight)) * 0.7f + tags)
    }

    private fun normalize(raw: String): String = raw.trim().lowercase()
        .replace("（", "(").replace("）", ")")
        .replace("：", ":").replace("！", "!").replace("？", "?")
        .replace("／", "/").replace("＆", "&").replace("－", "-")
        .replace(Regex("\\s+"), " ")

    private fun splitArtists(value: String): List<String> = normalize(value)
        .split(Regex("[,，、/\\\\&;；+]|\\s+(?:feat\\.?|ft\\.?)\\s+"))
        .map(String::trim)
        .filter(String::isNotBlank)

    private fun tagScore(a: String, b: String): Float {
        val tagsA = titleTags(a)
        val tagsB = titleTags(b)
        if (tagsA.isEmpty() && tagsB.isEmpty()) return 30f
        if (tagsA.isEmpty() || tagsB.isEmpty()) return 0f
        return tagsA.count { it in tagsB }.toFloat() / max(tagsA.size, tagsB.size) * 30f
    }

    private fun titleTags(value: String): Set<String> = Regex("(?:ver(?:sion)?\\.?|mix(?:ed)?|edit(?:ed)?|伴奏|纯音乐|inst(?:rumental)?|off\\s*vocal|tv\\s*size|live|remix|demo|cover|版)", RegexOption.IGNORE_CASE)
        .findAll(value)
        .map { it.value.lowercase().replace("version", "ver").replace("mixed", "mix").replace("edited", "edit") }
        .toSet()

    private fun commonPrefix(a: String, b: String): String {
        val end = minOf(a.length, b.length)
        var index = 0
        while (index < end && a[index] == b[index]) index++
        return a.substring(0, index)
    }

    private fun similarity(a: String, b: String): Float {
        val longer = if (a.length >= b.length) a else b
        val shorter = if (a.length >= b.length) b else a
        if (longer.isEmpty()) return 1f
        val previous = IntArray(shorter.length + 1) { it }
        val current = IntArray(shorter.length + 1)
        for (i in longer.indices) {
            current[0] = i + 1
            for (j in shorter.indices) {
                current[j + 1] = if (longer[i] == shorter[j]) previous[j] else 1 + minOf(previous[j], current[j], previous[j + 1])
            }
            for (j in previous.indices) previous[j] = current[j]
        }
        return (longer.length - previous[shorter.length]).toFloat() / longer.length
    }

    private fun isValidInfo(value: String): Boolean {
        val text = value.trim()
        if (text.isBlank()) return false
        val lower = text.lowercase()
        if (lower in setOf("unknown", "<unknown>", "未知歌曲", "未知歌手", "未知专辑", "unknown artist", "unknown album", "null", "n/a", "none")) return false
        if (text.all(Char::isDigit)) return false
        return text.count { !it.isLetterOrDigit() && !it.isWhitespace() } <= text.length / 2
    }
}
