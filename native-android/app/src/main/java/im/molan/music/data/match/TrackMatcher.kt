package im.molan.music.data.match

import im.molan.music.model.Track
import java.text.Normalizer
import kotlin.math.abs
import kotlin.math.max

/**
 * 线上 / 本地统一歌曲匹配器。
 *
 * 匹配按可信度分层：艺人+标题精确匹配、仅标题精确匹配、文件名匹配、
 * 去版本标签后的标题匹配，以及标题/艺人组合的模糊匹配。只有通过对应模式的
 * 保护门槛后，本地音源才会替代线上解析，兼顾本地优先与避免误播。
 */
object TrackMatcher {
    /** 常规模糊匹配仅作为严格的双信号兜底，不以低分相似度替代线上音源。 */
    const val ACCEPTANCE_SCORE = 68f
    /** 时长差超过半秒即视为不同版本，不能触发本地优先播放。 */
    private const val DURATION_TOLERANCE_MS = 500L

    enum class MatchMode {
        TITLE_ARTIST_EXACT,
        TITLE_EXACT,
        TITLE_VARIANT,
        FILE_NAME,
        FUZZY,
    }

    data class Result(
        val local: Track,
        val score: Float,
        val titleScore: Float,
        val artistScore: Float?,
        val albumScore: Float?,
        val mode: MatchMode,
    )

    /**
     * 用于内存候选索引的检索键。除了元数据标题，也为扫描和下载文件建立
     * 去扩展名、去标签、分隔符后半段等文件名键，因此“歌手 - 歌名.flac”
     * 一类文件即使缺少媒体标签也可以进入匹配候选集。
     */
    fun indexKeys(track: Track): Set<String> {
        val metadataTitles = titleForms(track.title)
        val fileTitles = track.localFileName?.let(::titleForms).orEmpty()
        val titles = (metadataTitles + fileTitles).filter { it.length >= 2 }.toSet()
        if (titles.isEmpty()) return emptySet()
        val artist = normalize(track.artist)
        return buildSet {
            titles.forEach { title ->
                // 仅用完整规范标题作为普通候选键；不再用短前缀，以免相同开头的歌曲进入候选集。
                add("t:$title")
                if (artist.isNotBlank()) add("at:$artist|$title")
            }
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
        .filter(::isAccepted)
        .maxWithOrNull(
            compareBy<Result> { it.score }
                .thenBy { if (it.local.source == Track.Source.DOWNLOADED) 1 else 0 },
        )

    fun score(target: Track, local: Track): Result? {
        if (!durationCompatible(target, local)) return null

        val targetTitles = titleForms(target.title)
        if (targetTitles.isEmpty()) return null
        val metadataScore = bestTextScore(targetTitles, titleForms(local.title))
        val filenameScore = local.localFileName?.let { bestTextScore(targetTitles, titleForms(it)) } ?: 0f
        val titleScore = max(metadataScore, filenameScore)
        // 名称本身未达到较高相似度时，时长接近或艺人偶然相同都不足以构成匹配。
        if (titleScore < 70f) return null

        val artistScore = optionalScore(target.artist, local.artist, ::artistScore)
        val albumScore = optionalScore(target.album, local.album, ::textScore)
        val fromFileName = filenameScore > metadataScore + 2f
        val titleExact = titleScore >= 99.5f
        val artistExact = artistScore != null && artistScore >= 99.5f
        val titleVariant = titleScore >= 88f

        val baseScore = when {
            artistScore != null && albumScore != null -> titleScore * 0.50f + artistScore * 0.35f + albumScore * 0.15f
            artistScore != null -> titleScore * 0.60f + artistScore * 0.40f
            albumScore != null -> titleScore * 0.75f + albumScore * 0.25f
            else -> titleScore
        }
        val finalScore = when {
            titleExact && artistExact -> 100f
            titleExact && artistScore == null -> max(baseScore, 88f)
            titleExact && (artistScore ?: 0f) >= 70f -> max(baseScore, 94f)
            // 已知艺人明显不同的同名歌曲不能靠标题单独命中。
            titleExact -> max(baseScore, 60f)
            fromFileName && titleVariant -> max(baseScore, if ((artistScore ?: 0f) >= 70f) 90f else 58f)
            titleVariant && (artistScore ?: 0f) >= 70f -> max(baseScore, 84f)
            else -> baseScore
        }.coerceIn(0f, 100f)

        val mode = when {
            titleExact && artistExact -> MatchMode.TITLE_ARTIST_EXACT
            titleExact -> MatchMode.TITLE_EXACT
            fromFileName && titleVariant -> MatchMode.FILE_NAME
            titleVariant -> MatchMode.TITLE_VARIANT
            else -> MatchMode.FUZZY
        }
        return Result(local, finalScore, titleScore, artistScore, albumScore, mode)
    }

    private fun isAccepted(result: Result): Boolean = when (result.mode) {
        MatchMode.TITLE_ARTIST_EXACT -> true
        // 单标题精确命中适合媒体标签缺失的本地库；若艺人明确冲突仍要求较高分。
        MatchMode.TITLE_EXACT -> result.score >= 88f && result.titleScore >= 99.5f
        MatchMode.TITLE_VARIANT -> result.score >= 84f && result.titleScore >= 92f && (result.artistScore ?: 0f) >= 70f
        MatchMode.FILE_NAME -> result.score >= 90f && result.titleScore >= 92f && (result.artistScore ?: 0f) >= 70f
        // 模糊匹配必须同时具备极高标题相似度与较强艺人相似度，绝不接受单一弱信号。
        MatchMode.FUZZY -> result.score >= ACCEPTANCE_SCORE && result.titleScore >= 90f && (result.artistScore ?: 0f) >= 70f
    }

    private fun durationCompatible(target: Track, local: Track): Boolean {
        if (target.durationMs <= 0L || local.durationMs <= 0L) return true
        return abs(target.durationMs - local.durationMs) <= DURATION_TOLERANCE_MS
    }

    private fun optionalScore(a: String, b: String, scorer: (String, String) -> Float): Float? =
        // 缺失标签不等价于冲突；明确且有效的双方标签才参与负向或正向评分。
        if (!isValidInfo(a) || !isValidInfo(b)) null else scorer(a, b)

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

    private fun bestTextScore(left: Set<String>, right: Set<String>): Float =
        left.maxOfOrNull { a -> right.maxOfOrNull { b -> textScore(a, b) } ?: 0f } ?: 0f

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

    /** 返回标题的多组等价形式：标准化、去括号版本标签、去合作艺人标签和文件名的后半段。 */
    private fun titleForms(raw: String): Set<String> {
        val source = raw.trim().replace(Regex("\\.(?:mp3|flac|m4a|aac|ogg|opus|wav)$", RegexOption.IGNORE_CASE), "")
        if (source.isBlank()) return emptySet()
        val canonical = normalize(source)
        val noBracket = canonical.replace(Regex("[\\[(（【][^\\])）】]{0,64}[\\])）】]"), " ").trim()
        val noVersion = noBracket.replace(VERSION_OR_FEATURE_TAG, " ").trim()
        val parts = source.split(Regex("\\s*(?:-|–|—|_|·)\\s*")).map(::normalize).filter { it.length >= 2 }
        return buildSet {
            listOf(canonical, noBracket, noVersion).filter { it.length >= 2 }.forEach(::add)
            // 常见文件名“歌手 - 歌名”以最后一段作为候选标题，完整名仍然保留。
            parts.lastOrNull()?.takeIf { it.length >= 2 }?.let(::add)
        }
    }

    private fun normalize(raw: String): String {
        val simplified = buildString {
            Normalizer.normalize(raw, Normalizer.Form.NFKD).forEach { char ->
                append(TRADITIONAL_TO_SIMPLIFIED[char] ?: char)
            }
        }
        return simplified.trim().lowercase()
            .replace(Regex("\\p{M}+"), "")
            .replace("（", "(").replace("）", ")")
            .replace("：", ":").replace("！", "!").replace("？", "?")
            .replace("／", "/").replace("＆", "&").replace("－", "-")
            .replace(Regex("[\\p{Punct}·]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun splitArtists(value: String): List<String> = normalize(value)
        .split(Regex("[,，、/\\\\&;；+]|\\s+(?:feat\\.?|ft\\.?)\\s+"))
        .map(String::trim)
        .filter(String::isNotBlank)

    private fun tagScore(a: String, b: String): Float {
        val tagsA = titleTags(a)
        val tagsB = titleTags(b)
        if (tagsA.isEmpty() && tagsB.isEmpty()) return 20f
        if (tagsA.isEmpty() || tagsB.isEmpty()) return 0f
        return tagsA.count { it in tagsB }.toFloat() / max(tagsA.size, tagsB.size) * 25f
    }

    private fun titleTags(value: String): Set<String> = VERSION_OR_FEATURE_TAG
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
        val shorter = if (a.length >= b.length) a else b
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

    private val VERSION_OR_FEATURE_TAG = Regex(
        "(?:ver(?:sion)?\\.?|mix(?:ed)?|edit(?:ed)?|feat\\.?|ft\\.?|伴奏|纯音乐|inst(?:rumental)?|off\\s*vocal|tv\\s*size|live|remix|demo|cover|版)",
        RegexOption.IGNORE_CASE,
    )

    /** 常见简繁标题异体统一；其余字符仍按原字符参与模糊匹配。 */
    private val TRADITIONAL_TO_SIMPLIFIED = mapOf(
        '後' to '后', '樂' to '乐', '愛' to '爱', '與' to '与', '為' to '为', '這' to '这', '個' to '个',
        '們' to '们', '裡' to '里', '裡' to '里', '無' to '无', '風' to '风', '雲' to '云', '開' to '开',
        '關' to '关', '聽' to '听', '說' to '说', '時' to '时', '間' to '间', '見' to '见', '轉' to '转',
        '會' to '会', '國' to '国', '龍' to '龙', '門' to '门', '聲' to '声', '夢' to '梦', '淚' to '泪',
        '遠' to '远', '還' to '还', '給' to '给', '讓' to '让', '從' to '从', '點' to '点', '長' to '长',
        '頭' to '头', '心' to '心', '萬' to '万', '畫' to '画', '車' to '车', '綠' to '绿', '黃' to '黄',
    )
}
