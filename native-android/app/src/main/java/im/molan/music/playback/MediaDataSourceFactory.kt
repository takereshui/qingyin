package im.molan.music.playback

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.CacheDataSource
import im.molan.music.data.online.OnlineCache

/**
 * 按 URI scheme 分流的数据源工厂：
 * - http/https 在线曲目 → CacheDataSource（边播边写在线缓存，LRU 上限淘汰）；
 * - content/file 本地与已下载曲目 → 原样直读，绝不写缓存，避免浪费缓存空间。
 */
@OptIn(UnstableApi::class)
class MediaDataSourceFactory(
    private val onlineCache: OnlineCache,
    private val base: DataSource.Factory,
) : DataSource.Factory {
    override fun createDataSource(): DataSource = RoutingDataSource(onlineCache, base)
}

@OptIn(UnstableApi::class)
private class RoutingDataSource(
    private val onlineCache: OnlineCache,
    private val base: DataSource.Factory,
) : DataSource {
    private var delegate: DataSource? = null
    private var pendingTransferListener: TransferListener? = null

    override fun addTransferListener(listener: TransferListener) {
        // 分流创建阶段尚无 DataSource；open 时把监听器接到实际委托上。
        pendingTransferListener = listener
    }

    override fun open(dataSpec: DataSpec): Long {
        val scheme = dataSpec.uri.scheme?.lowercase()
        val remote = scheme == "http" || scheme == "https" || scheme == "qingyin-queue"
        val created = if (remote) {
            CacheDataSource.Factory()
                .setCache(onlineCache.cache())
                .setUpstreamDataSourceFactory(base)
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
                .createDataSource()
        } else {
            base.createDataSource()
        }
        pendingTransferListener?.let { runCatching { created.addTransferListener(it) } }
        delegate = created
        return created.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        delegate?.read(buffer, offset, length) ?: C.RESULT_END_OF_INPUT

    override fun getUri(): Uri? = delegate?.uri

    override fun close() {
        delegate?.close()
        delegate = null
    }
}