package im.molan.music

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import java.io.File

class QingyinApplication : Application(), ImageLoaderFactory {
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
