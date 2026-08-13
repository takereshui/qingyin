package im.molan.music.data.online

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceInputStream
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File
import java.io.InputStream

/**
 * 在线试听缓存：与“下载”完全分离的临时空间。
 * 播放器通过 CacheDataSource 边播边写；超上限由 LRU 淘汰。
 * 缓存目录在应用私有 cacheDir，清理缓存不影响已下载文件。
 */
@OptIn(UnstableApi::class)
class OnlineCache(private val context: Context) {
    private val cacheDir = File(context.cacheDir, "online-cache")

    /** 当前生效的上限；修改后在下次重建缓存时按新上限生效。 */
    @Volatile
    var maxBytes: Long = DEFAULT_LIMIT

    @Volatile
    private var simpleCache: SimpleCache? = null

    /** 懒初始化缓存；PlaybackService 首次使用时调用。上限变更经 reconfigure 即时收缩，新上限在下次进程重建时生效。 */
    fun cache(limitBytes: Long = maxBytes): SimpleCache {
        maxBytes = limitBytes
        return synchronized(this) {
            simpleCache ?: SimpleCache(cacheDir, LeastRecentlyUsedCacheEvictor(limitBytes)).also {
                simpleCache = it
            }
        }
    }

    val spaceBytes: Long get() = runCatching { simpleCache?.getCacheSpace() ?: 0L }.getOrNull() ?: 0L

    fun clearAll() {
        runCatching { simpleCache?.evictAll() }
    }

    /**
     * 调整上限：缓存已建立且当前占用超新上限时，按占用从大到小移除整曲缓存。
     * 重新增长到新上限需缓存重建（重启应用或播放服务重启）。
     */
    fun reconfigure(limitBytes: Long) {
        maxBytes = limitBytes.coerceAtLeast(64L * 1024 * 1024)
        val cache = simpleCache ?: return
        runCatching {
            var remaining = cache.getCacheSpace()
            if (remaining <= maxBytes) return@runCatching
            val keys = cache.getKeys().sortedByDescending { cache.getCachedBytes(it) }
            for (key in keys) {
                if (remaining <= maxBytes) break
                val size = cache.getCachedBytes(key)
                if (size <= 0L) continue
                runCatching { cache.removeResource(key) }
                remaining -= size
            }
        }
    }

    fun isFullyCached(url: String): Boolean = runCatching {
        val cache = simpleCache ?: return@runCatching false
        val length = cache.getContentLength(url)
        length > 0L && cache.isCached(url, 0L, length)
    }.getOrDefault(false)

    /**
     * 只读打开已完整缓存的 URL 字节流（缓存命中时下载可“另存”，避免重新网络下载）。
     * 未完整缓存返回 null，调用方应走正常下载链路。
     */
    fun openCachedStream(url: String): InputStream? {
        val cache = simpleCache ?: return null
        return runCatching {
            val length = cache.getContentLength(url)
            if (length <= 0L || !cache.isCached(url, 0L, length)) return null
            val ds = CacheDataSource(cache, null, CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
            val spec = DataSpec.Builder().setUri(Uri.parse(url)).build()
            try {
                ds.open(spec)
                DataSourceInputStream(ds)
            } catch (e: Exception) {
                runCatching { ds.close() }
                throw e
            }
        }.getOrNull()
    }

    private fun close() {
        runCatching { simpleCache?.release() }
        simpleCache = null
    }

    companion object {
        val DEFAULT_LIMIT: Long = 2L * 1024 * 1024 * 1024
    }
}