package im.molan.music.data.match

import im.molan.music.model.Track

/**
 * 本地音源匹配的线程安全内存索引。
 *
 * 线上列表重组只应读取预计算的标志位；真正的模糊评分仅在后台预计算或用户点击播放时
 * 进入本类。索引替换会同时失效正、负缓存，避免旧扫描结果覆盖新的本地曲库。
 */
internal class LocalTrackMatchIndex(
    private val candidateFinder: (Track, List<Track>) -> TrackMatcher.Result? = TrackMatcher::findBest,
) {
    private val lock = Any()
    private var candidatesByKey: Map<String, List<Track>> = emptyMap()
    private val matchedCache = mutableMapOf<String, TrackMatcher.Result>()
    private val unmatchedCache = mutableSetOf<String>()

    fun replace(candidates: List<Track>) {
        val nextIndex = candidates
            .flatMap { candidate -> TrackMatcher.indexKeys(candidate).map { key -> key to candidate } }
            .groupBy({ it.first }, { it.second })
        synchronized(lock) {
            candidatesByKey = nextIndex
            matchedCache.clear()
            unmatchedCache.clear()
        }
    }

    fun find(remote: Track): TrackMatcher.Result? = synchronized(lock) {
        val cacheKey = "${remote.source.name}:${remote.id}"
        matchedCache[cacheKey]?.let { return@synchronized it }
        if (cacheKey in unmatchedCache) return@synchronized null

        val candidates = TrackMatcher.indexKeys(remote)
            .flatMap { key -> candidatesByKey[key].orEmpty() }
            .distinctBy(Track::id)
        if (candidates.isEmpty()) {
            unmatchedCache += cacheKey
            return@synchronized null
        }

        return@synchronized candidateFinder(remote, candidates).also { result ->
            if (result == null) unmatchedCache += cacheKey else matchedCache[cacheKey] = result
        }
    }
}
