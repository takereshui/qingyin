package im.molan.music.data.network

internal object PlaylistCachePolicy {
    fun <T> shouldServeFromCache(force: Boolean, cached: Pair<Boolean, T>?): Boolean {
        return !force && cached?.first == true
    }
}
