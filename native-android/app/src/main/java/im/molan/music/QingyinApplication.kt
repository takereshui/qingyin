package im.molan.music

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import im.molan.music.data.download.DownloadRepository
import java.io.File

class QingyinApplication : Application(), ImageLoaderFactory {
    /** 应用级唯一下载器实例：不随 ViewModel 或 Activity 生命周期销毁，下载队列独立存活。 */
    val downloadRepository: DownloadRepository by lazy { DownloadRepository(this) }

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
