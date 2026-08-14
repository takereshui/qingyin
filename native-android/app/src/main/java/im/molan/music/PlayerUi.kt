package im.molan.music

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import im.molan.music.model.AppSettings
import java.io.File
import im.molan.music.model.DownloadEntry
import im.molan.music.model.LyricLine
import im.molan.music.model.NcmQrLoginState
import im.molan.music.model.PlaybackMode
import im.molan.music.model.PlayerSnapshot
import im.molan.music.model.Track

@Composable
internal fun MiniPlayer(snapshot: PlayerSnapshot, model: MainViewModel, onQueue: () -> Unit, onOpen: () -> Unit) {
    val current = snapshot.current ?: return
    Column(
        Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(onClick = onOpen).padding(horizontal = 4.dp, vertical = 2.dp),
        ) {
            CoverArt(current, Modifier.size(56.dp), RoundedCornerShape(12.dp))
            Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) {
                Text(current.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                Text(current.artist, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = model.playback::toggle, modifier = Modifier.size(48.dp)) { Icon(if (snapshot.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, if (snapshot.isPlaying) "暂停" else "播放", Modifier.size(28.dp)) }
            IconButton(onClick = model.playback::next, modifier = Modifier.size(48.dp)) { Icon(Icons.Default.SkipNext, "下一首", Modifier.size(28.dp)) }
            IconButton(onClick = onQueue, modifier = Modifier.size(48.dp)) { Icon(Icons.AutoMirrored.Filled.QueueMusic, "播放队列", Modifier.size(25.dp)) }
        }
        val progress = (snapshot.positionMs.toFloat() / maxOf(snapshot.durationMs, current.durationMs, 1L).toFloat()).coerceIn(0f, 1f)
        Box(
            modifier = Modifier.fillMaxWidth().padding(start = 4.dp, top = 7.dp, end = 4.dp).height(4.dp)
                .clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)),
        ) {
            Box(
                modifier = Modifier.fillMaxWidth(progress).fillMaxSize()
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
    }
}

@Composable
internal fun QueueDialog(
    snapshot: PlayerSnapshot,
    model: MainViewModel,
    modifier: Modifier = Modifier,
    onDismiss: () -> Unit,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = 420.dp)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
        elevation = CardDefaults.cardElevation(defaultElevation = 10.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("当前播放 · ${modeLabel(snapshot.playbackMode)}", Modifier.weight(1f), fontWeight = FontWeight.Bold)
                IconButton(onClick = model.playback::cycleMode, modifier = Modifier.size(42.dp)) {
                    Icon(
                        if (snapshot.playbackMode == PlaybackMode.SHUFFLE) Icons.Default.Shuffle else Icons.Default.Repeat,
                        "切换播放模式",
                    )
                }
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
            HorizontalDivider()
            LazyColumn(Modifier.weight(1f, fill = false).fillMaxWidth()) {
                items(snapshot.queue, key = Track::id) { track ->
                    val index = snapshot.queue.indexOf(track)
                    Row(
                        Modifier.fillMaxWidth().clickable { model.playback.playQueue(snapshot.queue, index) }.padding(vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            if (index == snapshot.currentIndex) "♪" else "${index + 1}",
                            Modifier.width(28.dp),
                            color = if (index == snapshot.currentIndex) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Column(Modifier.weight(1f)) {
                            Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(track.artist, style = MaterialTheme.typography.labelSmall)
                        }
                        if (snapshot.queue.size > 1) {
                            IconButton(onClick = { model.playback.removeAt(index) }, modifier = Modifier.size(42.dp)) {
                                Text("×", style = MaterialTheme.typography.titleLarge)
                            }
                        }
                    }
                }
            }
            FilledTonalButton(onClick = model.playback::clearKeepingCurrent, modifier = Modifier.fillMaxWidth()) { Text("清空其余歌曲") }
        }
    }
}

@Composable
internal fun NcmQrLoginDialog(state: NcmQrLoginState, onDismiss: () -> Unit, onRefresh: () -> Unit) {
    val bitmap = remember(state.qrImage) {
        runCatching {
            val encoded = state.qrImage.substringAfter("base64,", state.qrImage)
            val bytes = Base64.decode(encoded, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        }.getOrNull()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("网易云二维码登录") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                when {
                    bitmap != null -> Image(bitmap, "网易云登录二维码", Modifier.size(220.dp))
                    state.stage == NcmQrLoginState.Stage.LOADING -> Text("正在生成二维码…")
                    else -> Text("二维码暂不可用")
                }
                Spacer(Modifier.height(12.dp))
                Text(state.message, color = if (state.stage == NcmQrLoginState.Stage.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { FilledTonalButton(onClick = onRefresh) { Text("刷新二维码") } },
        dismissButton = { FilledTonalButton(onClick = onDismiss) { Text("关闭") } },
    )
}

@Composable
internal fun FullPlayerDialog(snapshot: PlayerSnapshot, lyrics: List<LyricLine>, playbackError: String, downloadActionMessage: String, model: MainViewModel, onDismiss: () -> Unit) {
    val current = snapshot.current ?: return
    val duration = maxOf(snapshot.durationMs, current.durationMs, 1L)
    val activeLine = lyrics.indexOfLast { it.timeMs <= snapshot.positionMs + 80 }
    val pagerState = rememberPagerState(pageCount = { 2 })
    val lyricListState = rememberLazyListState()
    var sliderValue by remember(current.id) { mutableStateOf(0f) }
    var isSeeking by remember(current.id) { mutableStateOf(false) }

    // 播放器队列切歌时同步切换歌词会话，绝不继续保留上一首的歌词。
    LaunchedEffect(current.id, current.source) { model.ensureLyricsForCurrent(current) }
    LaunchedEffect(snapshot.positionMs, duration, isSeeking) {
        if (!isSeeking) sliderValue = (snapshot.positionMs.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
    }
    LaunchedEffect(activeLine, pagerState.currentPage, lyrics.size) {
        if (pagerState.currentPage == 1 && activeLine >= 0) {
            // 先保证目标行已进入测量范围；随后按实际 item 高度和实际 viewport 中点校正。
            // 这避免了用固定行高/固定 dp 偏移所造成的歌词总是靠近底部的问题。
            lyricListState.scrollToItem(activeLine)
            delay(32)
            val layout = lyricListState.layoutInfo
            val target = layout.visibleItemsInfo.firstOrNull { it.index == activeLine }
            if (target != null) {
                val viewportCenter = (layout.viewportStartOffset + layout.viewportEndOffset) / 2f
                val targetCenter = target.offset + target.size / 2f
                lyricListState.animateScrollBy(targetCenter - viewportCenter)
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        CoverBackdrop(current)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.26f), MaterialTheme.colorScheme.background.copy(alpha = 0.88f)))))
        Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 18.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
                Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                    Text(current.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(current.artist, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(current.resolvedQuality?.label ?: current.resolvedQqQuality?.label ?: "在线播放", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            }

            HorizontalPager(state = pagerState, modifier = Modifier.weight(1f).fillMaxWidth()) { page ->
                if (page == 0) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Card(
                            shape = RoundedCornerShape(30.dp),
                            colors = CardDefaults.cardColors(containerColor = Color.Transparent),
                            elevation = CardDefaults.cardElevation(defaultElevation = 16.dp),
                        ) {
                            CoverArt(current, Modifier.fillMaxWidth(0.88f).aspectRatio(1f), RoundedCornerShape(30.dp))
                        }
                        Spacer(Modifier.height(28.dp))
                        Text(current.title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(8.dp))
                        Text(current.artist, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(18.dp))
                        Text("左右滑动查看歌词", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else if (lyrics.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("暂无可用歌词", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    BoxWithConstraints(Modifier.fillMaxSize()) {
                        // 前后均预留半个歌词视口，确保第一句和最后一句也可真正抵达中线。
                        val lyricEdgePadding = maxOf(112.dp, maxHeight / 2 - 34.dp)
                        LazyColumn(
                            state = lyricListState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(
                                start = 18.dp,
                                top = lyricEdgePadding,
                                end = 18.dp,
                                bottom = lyricEdgePadding,
                            ),
                            verticalArrangement = Arrangement.spacedBy(22.dp),
                        ) {
                            itemsIndexed(lyrics, key = { _, line -> line.timeMs }) { index, line ->
                                val isActive = index == activeLine
                                Column {
                                    Text(
                                        line.text,
                                        style = if (isActive) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleLarge,
                                        color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.56f),
                                        fontWeight = if (isActive) FontWeight.ExtraBold else FontWeight.Medium,
                                    )
                                    line.translation?.let { translated ->
                                        Text(
                                            translated,
                                            style = if (isActive) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
                                            color = if (isActive) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.92f) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.54f),
                                            modifier = Modifier.padding(top = 6.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                repeat(2) { index ->
                    Box(
                        Modifier.padding(horizontal = 5.dp, vertical = 8.dp)
                            .size(if (pagerState.currentPage == index) 9.dp else 7.dp)
                            .clip(CircleShape)
                            .background(if (pagerState.currentPage == index) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                    )
                }
            }

            Slider(
                value = sliderValue,
                onValueChange = { sliderValue = it; isSeeking = true },
                onValueChangeFinished = {
                    model.playback.seekTo((sliderValue * duration).toLong())
                    isSeeking = false
                },
                modifier = Modifier.fillMaxWidth().height(34.dp),
            )
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatDuration((sliderValue * duration).toLong()), style = MaterialTheme.typography.bodyMedium)
                Text(formatDuration(duration), style = MaterialTheme.typography.bodyMedium)
            }
            if (playbackError.isNotBlank()) Text(playbackError, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (downloadActionMessage.isNotBlank()) Text(downloadActionMessage, style = MaterialTheme.typography.bodyMedium, color = if (downloadActionMessage.contains("失败") || downloadActionMessage.contains("无法")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth().height(76.dp)) {
                IconButton(onClick = model.playback::previous, modifier = Modifier.size(52.dp)) { Icon(Icons.Default.SkipPrevious, "上一首", Modifier.size(30.dp)) }
                IconButton(onClick = model.playback::toggle, modifier = Modifier.size(68.dp)) { Icon(if (snapshot.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, if (snapshot.isPlaying) "暂停" else "播放", Modifier.size(46.dp)) }
                IconButton(onClick = model.playback::next, modifier = Modifier.size(52.dp)) { Icon(Icons.Default.SkipNext, "下一首", Modifier.size(30.dp)) }
                IconButton(onClick = model.playback::cycleMode, modifier = Modifier.size(52.dp)) { Icon(if (snapshot.playbackMode == PlaybackMode.SHUFFLE) Icons.Default.Shuffle else Icons.Default.Repeat, modeLabel(snapshot.playbackMode), Modifier.size(26.dp)) }
                if (current.remoteUrl != null) IconButton(onClick = { model.enqueueDownload(current) }, modifier = Modifier.size(52.dp)) { Icon(Icons.Default.Download, "按所选音质下载到 Music/轻音", Modifier.size(26.dp)) }
            }
        }
    }
}

@Composable
internal fun CoverBackdrop(track: Track) {
    val context = LocalContext.current
    val source = track.artworkUri ?: track.uri
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        if (source != null) {
            AsyncImage(
                model = ImageRequest.Builder(context).data(source).size(960).memoryCachePolicy(CachePolicy.ENABLED).diskCachePolicy(CachePolicy.ENABLED).crossfade(false).build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().blur(42.dp),
                alpha = 0.76f,
            )
        }
    }
}

@Composable
internal fun CoverArt(track: Track, modifier: Modifier, shape: androidx.compose.ui.graphics.Shape) {
    CachedCoverImage(
        url = track.artworkUri?.toString() ?: track.uri?.toString(),
        contentDescription = "${track.title} 封面",
        modifier = modifier,
        shape = shape,
        localTrack = if (track.source == Track.Source.LOCAL || track.source == Track.Source.DOWNLOADED) track else null,
    )
}

/**
 * 封面加载：
 * - 网络封面：优先查 Room 里的本地路径引用加载磁盘封面；首次加载成功后落盘入库。
 * - 本地音源（localTrack 非空）：直接优先文件内嵌封面，提取一次即永久缓存；
 *   避免 MediaStore 专辑封面 URI 在多数设备上失效导致封面空白/错配。
 */
@Composable
internal fun CachedCoverImage(url: String?, contentDescription: String, modifier: Modifier, shape: androidx.compose.ui.graphics.Shape, placeholder: @Composable (() -> Unit)? = null, localTrack: Track? = null) {
    val context = LocalContext.current
    val app = context.applicationContext as QingyinApplication
    val scope = rememberCoroutineScope()
    val isRemote = url?.startsWith("http") == true
    var localFile by remember(url, localTrack?.id) { mutableStateOf<File?>(null) }
    LaunchedEffect(url, localTrack?.id) {
        localFile = when {
            isRemote -> runCatching { app.artworkStore.localPathFor(url.orEmpty()) }.getOrNull()
            localTrack != null -> runCatching { app.artworkStore.localEmbeddedArtwork(localTrack) }.getOrNull()
            else -> null
        }
    }
    val source = when {
        localFile != null -> localFile
        url != null -> Uri.parse(url)
        else -> null
    }
    if (source == null) {
        if (placeholder != null) placeholder()
        else Box(modifier.clip(shape).background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.MusicNote, null, tint = MaterialTheme.colorScheme.primary)
        }
    } else {
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(source)
                .size(600)
                .memoryCachePolicy(CachePolicy.ENABLED)
                .diskCachePolicy(CachePolicy.ENABLED)
                .crossfade(true)
                .listener(onSuccess = { _, result ->
                    if (isRemote && url != null && localFile == null) {
                        val bitmap = (result.drawable as? BitmapDrawable)?.bitmap
                        if (bitmap != null) scope.launch {
                            val file = app.artworkStore.fileFor(url)
                            runCatching {
                                java.io.FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                            }.onSuccess {
                                runCatching { app.artworkStore.remember(url, file) }
                            }
                        }
                    }
                })
                .build(),
            contentDescription = contentDescription,
            modifier = modifier.clip(shape).background(MaterialTheme.colorScheme.secondaryContainer),
        )
    }
}

@Composable
internal fun TrackRow(
    track: Track,
    matchedLocal: Boolean = false,
    onClick: () -> Unit,
    onCollect: (() -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoverArt(track, Modifier.size(60.dp), RoundedCornerShape(14.dp))
        Spacer(Modifier.width(14.dp)); Column(Modifier.weight(1f)) {
            Text(track.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
            val sourceLabel = when (track.source) { Track.Source.NETEASE -> "网易云"; Track.Source.QQ -> "QQ"; Track.Source.DOWNLOADED -> "已下载"; Track.Source.LOCAL -> "本地" }
            Text(listOf(sourceLabel, track.artist, track.album, formatDuration(track.durationMs)).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (matchedLocal) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(start = 8.dp, end = 2.dp)) {
                Icon(Icons.Default.CheckCircle, "已识别本地音源", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                Text("本地", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
        }
        onCollect?.let { collect ->
            IconButton(onClick = collect, modifier = Modifier.size(42.dp)) {
                Icon(Icons.Default.AddCircleOutline, "收藏到歌单", tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
internal fun EmptyHint(text: String) = Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) { Text(text, Modifier.padding(18.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }

@Composable
internal fun CreateLocalPlaylistDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建本地歌单") },
        text = {
            TextField(value = name, onValueChange = { name = it }, label = { Text("歌单名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        },
        confirmButton = { FilledTonalButton(onClick = { onCreate(name) }, enabled = name.isNotBlank()) { Text("创建") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
internal fun PlaylistImportDialog(source: Track.Source, onDismiss: () -> Unit, onImport: (String) -> Unit) {
    var input by remember { mutableStateOf("") }
    val sourceName = if (source == Track.Source.QQ) "QQ 音乐" else "网易云音乐"
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("导入 $sourceName 歌单") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    if (source == Track.Source.QQ) "粘贴公开 QQ 歌单分享链接或歌单 ID。该导入不需要 QQ 登录。" else "粘贴网易云公开歌单链接或歌单 ID。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextField(value = input, onValueChange = { input = it }, label = { Text("歌单链接或 ID") }, singleLine = false, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { FilledTonalButton(onClick = { onImport(input) }, enabled = input.isNotBlank()) { Text("导入") } },
        dismissButton = { FilledTonalButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
internal fun NcmAccountDialog(settings: AppSettings, onDismiss: () -> Unit, onLogout: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("网易云音乐") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(settings.ncmNickname.ifBlank { "已登录" }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("歌单会自动同步到“我的歌单”", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { FilledTonalButton(onClick = onDismiss) { Text("完成") } },
        dismissButton = { FilledTonalButton(onClick = onLogout) { Text("退出登录") } },
    )
}

@Composable
internal fun DonateDialog(onDismiss: () -> Unit) {
    var method by remember { mutableStateOf("wechat") }
    val context = LocalContext.current
    val bitmap = remember(method) {
        runCatching {
            context.assets.open("donate/$method.jpg").use { stream ->
                BitmapFactory.decodeStream(stream)?.asImageBitmap()
            }
        }.getOrNull()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("赞赏支持") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = { method = "wechat" }) { Text("微信") }
                    FilledTonalButton(onClick = { method = "alipay" }) { Text("支付宝") }
                }
                Spacer(Modifier.height(14.dp))
                if (bitmap != null) Image(bitmap, if (method == "wechat") "微信赞赏二维码" else "支付宝赞赏二维码", Modifier.size(230.dp))
                else Text("二维码资源不可用", color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(8.dp))
                Text("感谢你的支持", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { FilledTonalButton(onClick = onDismiss) { Text("关闭") } },
    )
}

@Composable
internal fun SettingsDialog(settings: AppSettings, cacheSpaceBytes: Long = 0L, onDismiss: () -> Unit, onSave: (AppSettings) -> Unit, onClearCache: () -> Unit = {}) {
    var primaryNcm by remember(settings) { mutableStateOf(settings.ncmcBaseUrl) }
    var backupNcm by remember(settings) { mutableStateOf(settings.backupNcmcBaseUrl) }
    var useBackupNcm by remember(settings) { mutableStateOf(settings.useBackupNcmc) }
    var chkszBase by remember(settings) { mutableStateOf(settings.chkszBaseUrl) }
    var apiKey by remember(settings) { mutableStateOf(settings.chkszApiKey) }
    var streamQuality by remember(settings) { mutableStateOf(settings.streamQuality) }
    var quality by remember(settings) { mutableStateOf(settings.quality) }
    var streamQqQuality by remember(settings) { mutableStateOf(settings.streamQqQuality) }
    var qqQuality by remember(settings) { mutableStateOf(settings.qqQuality) }
    var cacheLimitMb by remember(settings) { mutableStateOf((settings.cacheLimitBytes / (1024L * 1024L)).toString()) }
    var darkTheme by remember(settings) { mutableStateOf(settings.darkTheme) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("设置") },
        text = {
            LazyColumn(Modifier.height(440.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item { SettingRow("深色模式", "跟随你的界面偏好", Icons.Default.DarkMode, darkTheme) { darkTheme = !darkTheme } }
                item { Text("线上试听与下载", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold) }
                item {
                    Text(
                        "试听/在线播放使用“试听音质”并进入临时缓存；下载使用“下载音质”永久保存。两者完全独立。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                QualitySelector("网易云 · 试听音质", AppSettings.Quality.entries, streamQuality) { streamQuality = it }
                QualitySelector("网易云 · 下载音质", AppSettings.Quality.entries, quality) { quality = it }
                QualitySelector("QQ · 试听音质", AppSettings.QqQuality.entries, streamQqQuality) { streamQqQuality = it }
                QualitySelector("QQ · 下载音质", AppSettings.QqQuality.entries, qqQuality) { qqQuality = it }
                item { Text("在线试听缓存", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp)) }
                item {
                    Text(
                        "已用 ${formatBytes(cacheSpaceBytes)} / 上限 ${formatBytes((cacheLimitMb.toLongOrNull() ?: 0L) * 1024L * 1024L)}。缓存歌曲与下载歌曲互不影响，清空缓存不影响已下载音乐。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextField(
                            cacheLimitMb, { value -> if (value.length <= 6 && value.all { it.isDigit() }) cacheLimitMb = value },
                            modifier = Modifier.weight(1f),
                            label = { Text("缓存上限（MB）") },
                            singleLine = true,
                        )
                        Spacer(Modifier.width(8.dp))
                        FilledTonalButton(onClick = onClearCache, modifier = Modifier.height(48.dp)) { Text("清空缓存") }
                    }
                }
                item { Text("网易云数据服务", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp)) }
                item { TextField(primaryNcm, { primaryNcm = it }, modifier = Modifier.fillMaxWidth(), label = { Text("主 NCMC 地址") }, singleLine = true) }
                item { TextField(backupNcm, { backupNcm = it }, modifier = Modifier.fillMaxWidth(), label = { Text("备用 NCMC 地址（可选）") }, singleLine = true) }
                item { SettingRow("使用备用 NCMC", "主线路异常时可手动切换", Icons.Default.Refresh, useBackupNcm) { useBackupNcm = !useBackupNcm } }
                item { Text("QQ / ChKSz API", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp)) }
                item { TextField(chkszBase, { chkszBase = it }, modifier = Modifier.fillMaxWidth(), label = { Text("ChKSz 主地址") }, singleLine = true) }
                item { TextField(apiKey, { apiKey = it }, modifier = Modifier.fillMaxWidth(), label = { Text("ChKSz API Key（必填）") }, singleLine = true) }
                item { Text("NCMC 仅用于网易云搜索、歌单、歌词与登录等数据服务，不再获取任何音源。QQ 搜索及网易云播放/下载只使用你配置的 ChKSz 主线路，必须填写 API Key。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        },
        confirmButton = {
            FilledTonalButton(onClick = {
                onSave(settings.copy(
                    darkTheme = darkTheme,
                    streamQuality = streamQuality,
                    quality = quality,
                    streamQqQuality = streamQqQuality,
                    qqQuality = qqQuality,
                    cacheLimitBytes = (cacheLimitMb.toLongOrNull() ?: 0L).coerceAtLeast(64L) * 1024L * 1024L,
                    ncmcBaseUrl = primaryNcm,
                    backupNcmcBaseUrl = backupNcm,
                    useBackupNcmc = useBackupNcm,
                    chkszBaseUrl = chkszBase,
                    useChkszBackup = false,
                    chkszApiKey = apiKey,
                ))
            }) { Text("保存") }
        },
        dismissButton = { FilledTonalButton(onClick = onDismiss) { Text("取消") } },
    )
}

private fun <T : Any> LazyListScope.QualitySelector(title: String, entries: List<T>, selected: T, onSelect: (T) -> Unit) {
    item { Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp)) }
    items(entries) { option ->
        Card(
            Modifier.fillMaxWidth().clickable { onSelect(option) },
            colors = CardDefaults.cardColors(containerColor = if (selected == option) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                val label = when (option) {
                    is AppSettings.Quality -> option.label
                    is AppSettings.QqQuality -> option.label
                    else -> option.toString()
                }
                Text(label, Modifier.weight(1f))
                if (selected == option) Text("已选", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
internal fun SettingRow(title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector, enabled: Boolean, onToggle: () -> Unit) {
    Card(shape = RoundedCornerShape(18.dp)) { Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) { Icon(icon, null, tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) { Text(title, fontWeight = FontWeight.SemiBold); Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }; Switch(checked = enabled, onCheckedChange = { onToggle() }) } }
}

internal fun formatDuration(milliseconds: Long): String {
    val seconds = (milliseconds / 1000).coerceAtLeast(0)
    return "%d:%02d".format(seconds / 60, seconds % 60)
}

internal fun modeLabel(mode: PlaybackMode) = when (mode) { PlaybackMode.LOOP -> "列表循环"; PlaybackMode.SINGLE -> "单曲循环"; PlaybackMode.SHUFFLE -> "随机播放" }

internal fun downloadStatusLabel(status: DownloadEntry.Status) = when (status) {
    DownloadEntry.Status.QUEUED -> "等待中"
    DownloadEntry.Status.DOWNLOADING -> "下载中"
    DownloadEntry.Status.PAUSED -> "已暂停"
    DownloadEntry.Status.COMPLETED -> "已完成"
    DownloadEntry.Status.FAILED -> "下载失败"
    DownloadEntry.Status.MISSING -> "文件缺失"
}

internal fun formatBytes(bytes: Long): String = when {
    bytes < 1_024L -> "$bytes B"
    bytes < 1_024L * 1_024L -> "%.1f KB".format(bytes / 1_024f)
    bytes < 1_024L * 1_024L * 1_024L -> "%.1f MB".format(bytes / (1_024f * 1_024f))
    else -> "%.2f GB".format(bytes / (1_024f * 1_024f * 1_024f))
}