package im.molan.music.data.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistCachePolicyTest {
    @Test
    fun servesFreshCacheWhenNotForced() {
        assertTrue(PlaylistCachePolicy.shouldServeFromCache(force = false, cached = true to "cached"))
    }

    @Test
    fun skipsStaleCacheWhenNotForced() {
        assertFalse(PlaylistCachePolicy.shouldServeFromCache(force = false, cached = false to "cached"))
    }

    @Test
    fun skipsCacheWhenForced() {
        assertFalse(PlaylistCachePolicy.shouldServeFromCache(force = true, cached = true to "cached"))
    }

    @Test
    fun skipsCacheWhenMissing() {
        assertFalse(PlaylistCachePolicy.shouldServeFromCache<String>(force = false, cached = null))
    }
}
