package im.molan.music

import android.app.Application
import android.util.Log
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import im.molan.music.data.artwork.ArtworkStore
import im.molan.music.data.db.QingyinDatabase
import im.molan.music.data.download.DownloadRepository
import im.molan.music.data.online.OnlineCache
import im.molan.music.data.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File

class QingyinApplication : Application(), ImageLoaderFactory {
    /** 应用级唯一设置仓库：下载器与 ViewModel 共用同一实例。 */
    val settingsRepository: SettingsRepository by lazy { SettingsRepository(this) }
    /** 应用级唯一下载器实例：不随 ViewModel 或 Activity 生命周期销毁，下载队列独立存活。 */
    val downloadRepository: DownloadRepository by lazy { DownloadRepository(this, settingsRepository) }
    /** 歌词/封面等媒体元数据数据库。 */
    val database: QingyinDatabase by lazy { QingyinDatabase.getInstance(this) }
    /** 在线试听缓存：与下载完全分离。 */
    val onlineCache: OnlineCache by lazy { OnlineCache(this) }
    /** 封面本地落盘与入库。 */
    val artworkStore: ArtworkStore by lazy { ArtworkStore(this, database.artworkDao()) }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val defaultUncaughtHandler = runCatching { Thread.getDefaultUncaughtExceptionHandler() }.getOrNull()

    override fun onCreate() {
        super.onCreate()
        // 启动即把已保存的缓存上限接入在线缓存参数；真正的 SimpleCache 由播放服务首次使用时实例化。
        appScope.launch {
            runCatching { onlineCache.maxBytes = settingsRepository.settings.first().cacheLimitBytes }
        }
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            // 崩溃先落盘到私有目录，便于事后排查；再交给系统默认处理器结束进程。
            runCatching {
                File(filesDir, "qingyin-crash.log").appendText(
                    buildString {
                        appendLine("=== ${System.currentTimeMillis()} ${thread.name} ===")
                        appendLine(Log.getStackTraceString(throwable))
                    },
                )
            }
            defaultUncaughtHandler?.uncaughtException(thread, throwable)
        }
    }

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .respectCacheHeaders(false)
        .diskCache {
            DiskCache.Builder()
                .directory(File(cacheDir, "covers-v2"))
                .maxSizeBytes(120L * 1024L * 1024L)
                .build()
        }
        .build()
}
