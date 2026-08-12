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
    private var controller: MediaController? = null

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = publish(player)
        override fun onPlayerError(error: androidx.media3.common.PlaybackException) = publish(controller)
    }

    init {
        val token = SessionToken(appContext, ComponentName(appContext, PlaybackService::class.java))
        val future = MediaController.Builder(appContext, token).buildAsync()
        future.addListener({
            runCatching { future.get() }.onSuccess { mediaController ->
                controller = mediaController
                mediaController.addListener(listener)
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
        val mediaController = controller ?: return
        if (tracks.isEmpty()) return
        mediaController.setMediaItems(tracks.map(Track::toMediaItem), startIndex.coerceIn(0, tracks.lastIndex), 0L)
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
        val queue = buildList {
            for (index in 0 until player.mediaItemCount) add(player.getMediaItemAt(index).toTrack())
        }
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
