package im.molan.music.playback

import android.content.ComponentName
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import im.molan.music.model.PlaybackMode
import im.molan.music.model.PlayerSnapshot
import im.molan.music.model.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class PlaybackConnection(context: Context) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _snapshot = MutableStateFlow(PlayerSnapshot())
    val snapshot: StateFlow<PlayerSnapshot> = _snapshot.asStateFlow()
    private val _errorMessage = MutableStateFlow("")
    val errorMessage: StateFlow<String> = _errorMessage.asStateFlow()
    private var controller: MediaController? = null
    private var pendingPlayback: Pair<List<Track>, Int>? = null
    private var cachedQueueIds: List<String> = emptyList()
    private var cachedQueue: List<Track> = emptyList()

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (player.playbackState == Player.STATE_READY) _errorMessage.value = ""
            publish(player)
        }
        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            _errorMessage.value = "在线音源播放失败：${error.errorCodeName}"
            publish(controller)
        }
    }

    init {
        val token = SessionToken(appContext, ComponentName(appContext, PlaybackService::class.java))
        val future = MediaController.Builder(appContext, token).buildAsync()
        future.addListener({
            runCatching { future.get() }.onSuccess { mediaController ->
                controller = mediaController
                mediaController.addListener(listener)
                pendingPlayback?.let { (tracks, index) ->
                    pendingPlayback = null
                    startPlayback(mediaController, tracks, index)
                }
                publish(mediaController)
            }
        }, ContextCompat.getMainExecutor(appContext))
        scope.launch {
            while (isActive) {
                controller?.let(::publish)
                delay(500)
            }
        }
    }

    fun playQueue(tracks: List<Track>, startIndex: Int) {
        if (tracks.isEmpty()) return
        val safeIndex = startIndex.coerceIn(0, tracks.lastIndex)
        val mediaController = controller
        if (mediaController == null) {
            pendingPlayback = tracks to safeIndex
            return
        }
        startPlayback(mediaController, tracks, safeIndex)
    }

    private fun startPlayback(mediaController: MediaController, tracks: List<Track>, startIndex: Int) {
        _errorMessage.value = ""
        mediaController.setMediaItems(tracks.map(Track::toMediaItem), startIndex, 0L)
        // 线上歌单的未解析项先完整入队但不预加载；ViewModel 替换当前项地址后才 prepare/play。
        val selected = tracks[startIndex]
        val readyNow = selected.uri != null || selected.remoteUrl?.startsWith("https://") == true || selected.remoteUrl?.startsWith("http://") == true
        if (readyNow) {
            mediaController.prepare()
            mediaController.play()
        } else {
            mediaController.pause()
        }
        publish(mediaController)
    }

    /** 用解析后的同源地址替换当前队列项，队列顺序和当前索引均保持不变。 */
    fun replaceCurrentAndPlay(track: Track) {
        val mediaController = controller ?: return
        val index = mediaController.currentMediaItemIndex.takeIf { it != C.INDEX_UNSET } ?: return
        mediaController.replaceMediaItem(index, track.toMediaItem())
        if (index in cachedQueue.indices) {
            cachedQueue = cachedQueue.toMutableList().apply { set(index, track) }
        }
        mediaController.prepare()
        mediaController.play()
        publish(mediaController)
    }

    fun toggle() {
        controller?.let { if (it.isPlaying) it.pause() else it.play() }
    }

    fun next() { controller?.seekToNextMediaItem() }
    fun previous() { controller?.seekToPreviousMediaItem() }
    fun seekTo(positionMs: Long) { controller?.seekTo(positionMs.coerceAtLeast(0L)) }

    fun cycleMode() {
        val mediaController = controller ?: return
        when {
            !mediaController.shuffleModeEnabled && mediaController.repeatMode == Player.REPEAT_MODE_ALL -> {
                mediaController.repeatMode = Player.REPEAT_MODE_ONE
            }
            !mediaController.shuffleModeEnabled && mediaController.repeatMode == Player.REPEAT_MODE_ONE -> {
                mediaController.repeatMode = Player.REPEAT_MODE_ALL
                mediaController.shuffleModeEnabled = true
            }
            else -> {
                mediaController.shuffleModeEnabled = false
                mediaController.repeatMode = Player.REPEAT_MODE_ALL
            }
        }
        publish(mediaController)
    }

    fun removeAt(index: Int) {
        controller?.takeIf { index in 0 until it.mediaItemCount && it.mediaItemCount > 1 }
            ?.removeMediaItem(index)
    }

    fun clearKeepingCurrent() {
        val mediaController = controller ?: return
        val current = mediaController.currentMediaItem ?: return
        mediaController.setMediaItem(current, 0L)
        publish(mediaController)
    }

    fun release() {
        controller?.removeListener(listener)
        controller?.release()
        controller = null
        scope.cancel()
    }

    private fun publish(player: Player?) {
        if (player == null) return
        val queueIds = buildList {
            for (index in 0 until player.mediaItemCount) add(player.getMediaItemAt(index).mediaId)
        }
        if (queueIds != cachedQueueIds || (queueIds.isEmpty() && cachedQueue.isNotEmpty())) {
            cachedQueueIds = queueIds
            cachedQueue = buildList {
                for (index in 0 until player.mediaItemCount) add(player.getMediaItemAt(index).toTrack())
            }
        }
        val queue = cachedQueue
        val currentIndex = player.currentMediaItemIndex.takeIf { it != C.INDEX_UNSET } ?: -1
        _snapshot.value = PlayerSnapshot(
            queue = queue,
            currentIndex = currentIndex,
            isPlaying = player.isPlaying,
            positionMs = player.currentPosition.coerceAtLeast(0L),
            durationMs = player.duration.takeIf { it != C.TIME_UNSET }?.coerceAtLeast(0L) ?: 0L,
            playbackMode = when {
                player.shuffleModeEnabled -> PlaybackMode.SHUFFLE
                player.repeatMode == Player.REPEAT_MODE_ONE -> PlaybackMode.SINGLE
                else -> PlaybackMode.LOOP
            },
            isLoading = player.playbackState == Player.STATE_BUFFERING,
        )
    }
}
