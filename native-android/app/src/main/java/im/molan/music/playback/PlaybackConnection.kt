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
    /** 结构或单曲替换都会自增；publish 据此重建队列缓存（同 id 换 URL 也需重建）。 */
    private var queueVersion = 0

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (player.playbackState == Player.STATE_READY) _errorMessage.value = ""
            // 媒体项结构变化时重建队列缓存；纯播放状态/进度走轻量 publishPosition。
            if (events.contains(Player.EVENT_TIMELINE_CHANGED) ||
                events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION) ||
                events.contains(Player.EVENT_TRACKS_CHANGED) ||
                events.contains(Player.EVENT_REPEAT_MODE_CHANGED) ||
                events.contains(Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED)
            ) {
                publish(player)
            } else {
                publishPosition(player)
            }
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
        // 位置/播放状态轮询只做轻量发布，绝不逐条遍历 MediaItem。
        scope.launch {
            while (isActive) {
                controller?.let(::publishPosition)
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
        val readyNow = isTrackPlayable(selected)
        if (readyNow) {
            mediaController.prepare()
            mediaController.play()
        } else {
            mediaController.pause()
        }
        // 显式标记队列重建，即使同一批 id 重入也刷新缓存。
        queueVersion++
        publish(mediaController)
    }

    /** 检查曲目是否具备立即播放的条件：本地音源或未过期的线上链路。 */
    fun isTrackPlayable(track: Track): Boolean {
        if (track.uri != null) return true
        val url = track.remoteUrl ?: return false
        if (!url.startsWith("https://") && !url.startsWith("http://")) return false
        // 线上 API 链路通常有几小时有效期；若超过 3 小时则视为过期，需由 ViewModel 触发重新解析。
        val ageMs = System.currentTimeMillis() - track.resolvedAt
        return ageMs in 0..(3L * 60 * 60 * 1000)
    }

    /** 用解析后的同源地址替换当前队列项，队列顺序和当前索引均保持不变。 */
    fun replaceCurrentAndPlay(track: Track) {
        val mediaController = controller ?: return
        val index = mediaController.currentMediaItemIndex.takeIf { it != C.INDEX_UNSET } ?: return
        mediaController.replaceMediaItem(index, track.toMediaItem())
        queueVersion++
        if (index in cachedQueue.indices) {
            cachedQueue = cachedQueue.toMutableList().apply { set(index, track) }
        }
        mediaController.prepare()
        mediaController.play()
        publish(mediaController)
    }

    /** 静默更新队列中指定位置的媒体项地址，不中断当前播放。用于后台预解析。 */
    fun updateQueueItem(index: Int, track: Track) {
        val mediaController = controller ?: return
        if (index !in 0 until mediaController.mediaItemCount) return
        val existing = mediaController.getMediaItemAt(index)
        if (existing.mediaId != track.id) return
        mediaController.replaceMediaItem(index, track.toMediaItem())
        queueVersion++
        if (index in cachedQueue.indices) {
            cachedQueue = cachedQueue.toMutableList().apply { set(index, track) }
        }
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
        val mediaController = controller
        if (mediaController == null || index !in 0 until mediaController.mediaItemCount || mediaController.mediaItemCount <= 1) return
        queueVersion++
        mediaController.removeMediaItem(index)
    }

    fun clearKeepingCurrent() {
        val mediaController = controller ?: return
        val current = mediaController.currentMediaItem ?: return
        mediaController.setMediaItem(current, 0L)
        queueVersion++
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
        // 结构事件（入队/移除/替换）才重建队列缓存；位置轮询绝不走到这里，避免千首队列每 500ms 全遍历。
        val ids = buildList { for (index in 0 until player.mediaItemCount) add(player.getMediaItemAt(index).mediaId) }
        if (ids != cachedQueueIds || queueVersion != lastPublishedQueueVersion) {
            cachedQueueIds = ids
            cachedQueue = buildList { for (index in 0 until player.mediaItemCount) add(player.getMediaItemAt(index).toTrack()) }
            lastPublishedQueueVersion = queueVersion
        }
        publishFields(player)
    }

    /** 轻量发布：只更新播放状态/位置/索引，避免每 500ms 遍历全部 MediaItem。 */
    private fun publishPosition(player: Player?) {
        if (player == null) return
        publishFields(player)
    }

    private fun publishFields(player: Player) {
        val snapshot = PlayerSnapshot(
            queue = cachedQueue,
            currentIndex = player.currentMediaItemIndex.takeIf { it != C.INDEX_UNSET } ?: -1,
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
        publishSnapshot(snapshot)
    }

    private var lastPublished: PlayerSnapshot? = null
    private var lastPublishedQueueVersion = -1

    /** 相同状态不重复发布，减少 Compose 重组频率。 */
    private fun publishSnapshot(snapshot: PlayerSnapshot) {
        val previous = lastPublished
        if (previous != null && previous == snapshot) return
        lastPublished = snapshot
        _snapshot.value = snapshot
    }
}
