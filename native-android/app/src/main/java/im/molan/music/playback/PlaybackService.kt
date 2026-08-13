package im.molan.music.playback

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import coil.ImageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var imageLoader: ImageLoader

    private val sessionCallback = object : MediaSession.Callback {
        /**
         * 当媒体项入队或切换时，拦截并注入封面位图。
         * 系统媒体通知栏（Notification）通常需要完整的 Bitmap 才能显示封面。
         */
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>
        ): ListenableFuture<List<MediaItem>> {
            val updatedItems = mediaItems.map { item ->
                if (item.mediaMetadata.artworkData != null || item.mediaMetadata.artworkUri == null) {
                    item
                } else {
                    // 先按原样入队以保证起播速度；位图加载成功后再通过 replaceMediaItem 更新。
                    fetchArtworkAndCover(mediaSession, item)
                    item
                }
            }
            return Futures.immediateFuture(updatedItems)
        }

        /** 支持 Android 13+ 系统的媒体控件恢复播放（Playback Resumption） */
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val player = mediaSession.player
            val items = mutableListOf<MediaItem>()
            for (i in 0 until player.mediaItemCount) {
                val item = player.getMediaItemAt(i)
                items.add(item)
                if (item.mediaMetadata.artworkData == null) fetchArtworkAndCover(mediaSession, item)
            }
            return Futures.immediateFuture(
                MediaSession.MediaItemsWithStartPosition(items, player.currentMediaItemIndex, player.currentPosition)
            )
        }
    }

    private fun fetchArtworkAndCover(session: MediaSession, item: MediaItem) {
        val uri = item.mediaMetadata.artworkUri ?: return
        serviceScope.launch {
            val bitmap = loadBitmap(uri.toString()) ?: return@launch
            val stream = java.io.ByteArrayOutputStream()
            if (bitmap.compress(Bitmap.CompressFormat.JPEG, 75, stream)) {
                val data = stream.toByteArray()
                withContext(Dispatchers.Main) {
                    val player = session.player
                    // 遍历寻找队列中匹配的项并更新其元数据（注入位图数据）
                    for (i in 0 until player.mediaItemCount) {
                        val currentItem = player.getMediaItemAt(i)
                        if (currentItem.mediaId == item.mediaId) {
                            val newMetadata = currentItem.mediaMetadata.buildUpon()
                                .setArtworkData(data, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                                .build()
                            val newItem = currentItem.buildUpon()
                                .setMediaMetadata(newMetadata)
                                .build()
                            // 仅当该项仍在队列中时进行原位替换
                            player.replaceMediaItem(i, newItem)
                        }
                    }
                }
            }
        }
    }

    private suspend fun loadBitmap(url: String): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            val request = ImageRequest.Builder(this@PlaybackService)
                .data(url)
                .size(600) // 限制封面位图尺寸，平衡清晰度与内存占用
                .allowHardware(false)
                .build()
            val result = imageLoader.execute(request)
            if (result is SuccessResult) (result.drawable as? BitmapDrawable)?.bitmap else null
        }.getOrNull()
    }

    override fun onCreate() {
        super.onCreate()
        imageLoader = ImageLoader(this)
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
            )
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
            .setDefaultRequestProperties(
                mapOf("Referer" to "https://music.163.com/")
            )
        val dataSourceFactory = DefaultDataSource.Factory(this, httpFactory)
        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(dataSourceFactory))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
            .apply {
                repeatMode = Player.REPEAT_MODE_ALL
                shuffleModeEnabled = false
            }
        mediaSession = MediaSession.Builder(this, player)
            .setCallback(sessionCallback)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: android.content.Intent?) {
        if (!isPlaybackOngoing) stopSelf()
    }

    override fun onDestroy() {
        serviceScope.cancel()
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }
}
