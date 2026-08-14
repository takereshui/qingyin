package im.molan.music

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import im.molan.music.model.AppSettings
import im.molan.music.model.DownloadEntry
import im.molan.music.model.PlaylistDetail
import im.molan.music.model.PlaylistSummary
import im.molan.music.model.Track

@Composable
internal fun HomeScreen(
    tracks: List<Track>,
    dailyTracks: List<Track>,
    dailyMessage: String,
    matchFlags: Map<String, Boolean>,
    model: MainViewModel,
    onOpenLocal: () -> Unit,
    onOpenQueue: () -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                shape = RoundedCornerShape(28.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            ) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("音乐，刚刚好", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("本地优先，在线随听", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.78f))
                }
            }
        }
        item { Text("快捷入口", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                QuickCard("本地音乐", "${tracks.size} 首", Icons.Default.LibraryMusic, Modifier.weight(1f), onClick = onOpenLocal)
                QuickCard("播放队列", "原生管理", Icons.AutoMirrored.Filled.QueueMusic, Modifier.weight(1f), onClick = onOpenQueue)
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("每日推荐", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                IconButton(onClick = { model.loadDaily(force = true) }) { Icon(Icons.Default.Refresh, "刷新每日推荐") }
            }
        }
        if (dailyMessage.isNotBlank()) item { Text(dailyMessage, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (dailyTracks.isNotEmpty()) items(dailyTracks.take(12), key = { "daily:${it.id}" }) { track -> TrackRow(track, matchedLocal = matchFlags[track.id] == true, onClick = { model.playDaily(track) }) }
        item { Text("最近扫描", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp)) }
        if (tracks.isEmpty()) item { EmptyHint("尚未扫描本地音乐。请到“本地”页面授权并扫描。") }
        else items(tracks.take(8), key = Track::id) { TrackRow(it, onClick = { model.playLocal(it) }) }
    }
}

@Composable
internal fun SearchScreen(searchTracks: List<Track>, message: String, playbackError: String, matchFlags: Map<String, Boolean>, model: MainViewModel) {
    var query by remember { mutableStateOf("") }
    var source by remember { mutableStateOf(Track.Source.NETEASE) }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Text("线上搜索", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { source = Track.Source.NETEASE }, modifier = Modifier.height(40.dp)) { Text(if (source == Track.Source.NETEASE) "网易云 ✓" else "网易云") }
                FilledTonalButton(onClick = { source = Track.Source.QQ }, modifier = Modifier.height(40.dp)) { Text(if (source == Track.Source.QQ) "QQ 音乐 ✓" else "QQ 音乐") }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.TextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    label = { Text("歌曲、歌手或专辑") },
                )
                Spacer(Modifier.width(8.dp))
                FilledTonalButton(onClick = { model.searchOnline(source, query) }, modifier = Modifier.height(48.dp)) { Text("搜索") }
            }
        }
        item {
            Text(
                if (source == Track.Source.QQ) "QQ 使用 ChKSz API 与独立 QQ 音质档位；请先在设置中填写 API Key。" else "网易云音源使用 ChKSz API 与独立网易云音质档位；NCMC 仅提供搜索、歌单和歌词等数据服务。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item { Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (playbackError.isNotBlank()) item { Text(playbackError, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        if (searchTracks.isEmpty()) item { EmptyHint("选择来源并输入关键词后搜索在线音乐") }
        else items(searchTracks, key = Track::id) { track -> TrackRow(track, matchedLocal = matchFlags[track.id] == true, onClick = { model.playOnline(track) }) }
    }
}

@Composable
internal fun QuickCard(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
) {
    Card(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
    ) {
        Column(Modifier.padding(16.dp)) {
            Icon(icon, title, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(12.dp))
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun LocalScreen(tracks: List<Track>, folderUris: List<String>, message: String, model: MainViewModel, onRequestPermission: () -> Unit, onPickFolder: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) { Text("本地音乐", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold); Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilledTonalButton(onClick = onPickFolder) { Text("添加文件夹") }
                    FilledTonalButton(onClick = { model.scanLocalMusic() }) { Icon(Icons.Default.Refresh, null); Spacer(Modifier.width(4.dp)); Text("扫描") }
                }
            }
        }
        item {
            Card(shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("扫描目录（${folderUris.size}）", fontWeight = FontWeight.SemiBold)
                    if (folderUris.isEmpty()) Text("尚未添加自定义目录；也会扫描系统媒体库。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    folderUris.forEach { uri ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Text(uri.substringAfterLast('/').ifBlank { uri }, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = { model.removeCustomFolder(uri) }) { Text("移除") }
                        }
                    }
                }
            }
        }
        if (tracks.isEmpty()) item { EmptyHint("轻音只扫描系统媒体库或你选择的文件夹，不导入或复制你的文件。") }
        else items(tracks, key = Track::id) { TrackRow(it, onClick = { model.playLocal(it) }) }
    }
}

@Composable
internal fun DownloadsScreen(entries: List<DownloadEntry>, tracks: List<Track>, downloadFolderUri: String, message: String, model: MainViewModel, onPickFolder: () -> Unit, onClearFolder: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text("下载", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("由轻音内置下载器管理，最多同时下载 3 首", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = model::refreshDownloads, modifier = Modifier.size(44.dp)) { Icon(Icons.Default.Refresh, "刷新下载列表") }
            }
        }
        item {
            Card(shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("下载目录", fontWeight = FontWeight.SemiBold)
                    Text(
                        if (downloadFolderUri.isBlank()) "默认：系统 Music/轻音下载（对系统媒体库和外部播放器可见）" else "自定义目录：${downloadFolderUri.substringAfterLast('/').ifBlank { downloadFolderUri }}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = onPickFolder, modifier = Modifier.height(36.dp)) { Text("选择目录") }
                        if (downloadFolderUri.isNotBlank()) {
                            TextButton(onClick = onClearFolder) { Text("恢复默认") }
                        }
                    }
                }
            }
        }
        item { Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (entries.isNotEmpty()) {
            item { Text("内置下载任务", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
            items(entries, key = DownloadEntry::id) { entry ->
                DownloadTaskRow(
                    entry = entry,
                    onRetry = { model.retryDownload(entry.id) },
                    onRemove = { model.removeDownloadRecord(entry.id) },
                )
            }
        }
        item { Text("已下载音乐", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp)) }
        if (tracks.isEmpty()) item { EmptyHint("尚未发现已完成的音乐。下载完成后会写入 Music/轻音下载 并自动进入本地扫描。") }
        else items(tracks, key = Track::id) { track -> TrackRow(track, onClick = { model.playDownloaded(track) }) }
    }
}

@Composable
internal fun DownloadTaskRow(entry: DownloadEntry, onRetry: () -> Unit, onRemove: () -> Unit) {
    val progress = if (entry.totalBytes > 0L) (entry.bytesDownloaded.toFloat() / entry.totalBytes.toFloat()).coerceIn(0f, 1f) else 0f
    Card(shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(entry.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(entry.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (entry.status == DownloadEntry.Status.FAILED) {
                        TextButton(onClick = onRetry) { Text("重试") }
                    } else {
                        Text(downloadStatusLabel(entry.status), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    }
                    IconButton(onClick = onRemove, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Default.Delete, "删除下载记录")
                    }
                }
            }
            if (entry.status == DownloadEntry.Status.FAILED && !entry.errorMessage.isNullOrBlank()) {
                Text(entry.errorMessage, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 4.dp))
            }
            if (entry.status == DownloadEntry.Status.DOWNLOADING || entry.status == DownloadEntry.Status.QUEUED || entry.status == DownloadEntry.Status.PAUSED) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(4.dp)))
                Spacer(Modifier.height(4.dp))
                Text("${formatBytes(entry.bytesDownloaded)} / ${if (entry.totalBytes > 0L) formatBytes(entry.totalBytes) else "未知大小"}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
internal fun PlaylistHubScreen(playlists: List<PlaylistSummary>, playlistMessage: String, model: MainViewModel, onCreateLocal: () -> Unit) {
    val online = playlists.filter { it.source != Track.Source.LOCAL }
    val local = playlists.filter { it.source == Track.Source.LOCAL }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) { Text("歌单", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold); Text("线上同步与本地自建歌单", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                FilledTonalButton(onClick = onCreateLocal) { Text("新建本地") }
            }
        }
        if (playlistMessage.isNotBlank()) item { Text(playlistMessage, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item { Text("线上歌单", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
        if (online.isEmpty()) item { EmptyHint("登录网易云或导入 QQ / 网易云歌单后，会在这里同步显示。") }
        items(online.chunked(2), key = { pair -> pair.joinToString("-") { it.id } }) { pair ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                pair.forEach { playlist -> PlaylistCard(playlist, Modifier.weight(1f), onClick = { model.openPlaylist(playlist) }, onRefresh = { model.openPlaylist(playlist, force = true) }, onSync = { model.syncPlaylistToLocal(playlist) }) }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        item { Text("本地歌单", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp)) }
        if (local.isEmpty()) item { EmptyHint("点击右上角“新建本地”创建一个本地歌单。") }
        items(local.chunked(2), key = { pair -> pair.joinToString("-") { it.id } }) { pair ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                pair.forEach { playlist -> PlaylistCard(playlist, Modifier.weight(1f), onClick = { model.openPlaylist(playlist) }, onRefresh = { model.deleteLocalPlaylist(playlist) }) }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
internal fun MineScreen(settings: AppSettings, model: MainViewModel, onNcmLogin: () -> Unit, onNcmAccount: () -> Unit, onImportPlaylist: (Track.Source) -> Unit, onSettings: () -> Unit, onDonate: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("我的", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                IconButton(onClick = onSettings, modifier = Modifier.size(44.dp)) { Icon(Icons.Default.MoreVert, "设置") }
            }
        }
        item {
            FilledTonalButton(
                onClick = if (settings.ncmCookie.isBlank()) onNcmLogin else onNcmAccount,
                modifier = Modifier.fillMaxWidth().height(44.dp),
            ) {
                Icon(Icons.Default.AccountCircle, null, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (settings.ncmCookie.isBlank()) "登录网易云音乐" else "网易云 · ${settings.ncmNickname.ifBlank { "已登录" }}")
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                FilledTonalButton(onClick = { onImportPlaylist(Track.Source.NETEASE) }, modifier = Modifier.weight(1f).height(44.dp)) { Text("导入网易云歌单") }
                FilledTonalButton(onClick = { onImportPlaylist(Track.Source.QQ) }, modifier = Modifier.weight(1f).height(44.dp)) { Text("导入 QQ 歌单") }
            }
        }
        item {
            Card(shape = RoundedCornerShape(16.dp)) {
                Text("歌单已移动到下方独立的“歌单”入口。这里保留账号、导入、设置和赞赏功能。", Modifier.padding(14.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item { FilledTonalButton(onClick = onDonate, modifier = Modifier.fillMaxWidth().height(44.dp)) { Text("赞赏支持") } }
    }
}

@Composable
internal fun PlaylistCard(playlist: PlaylistSummary, modifier: Modifier, onClick: () -> Unit, onRefresh: () -> Unit, onSync: (() -> Unit)? = null) {
    var menuExpanded by remember { mutableStateOf(false) }
    Card(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
    ) {
        Column(Modifier.padding(10.dp)) {
            Box {
                PlaylistCover(playlist, Modifier.fillMaxWidth().height(148.dp))
                Box(Modifier.align(Alignment.TopEnd).padding(4.dp)) {
                    IconButton(
                        onClick = { menuExpanded = true },
                        modifier = Modifier.size(38.dp).background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.28f), CircleShape),
                    ) { Icon(Icons.Default.MoreVert, "歌单操作", tint = Color.White) }
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        DropdownMenuItem(
                            text = { Text("打开歌单") },
                            onClick = { menuExpanded = false; onClick() },
                        )
                        DropdownMenuItem(
                            text = { Text("刷新歌单") },
                            onClick = { menuExpanded = false; onRefresh() },
                        )
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(playlist.name, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyLarge)
            Text("${playlist.trackCount} 首", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (onSync != null) {
                Spacer(Modifier.height(8.dp))
                FilledTonalButton(onClick = onSync, modifier = Modifier.fillMaxWidth().height(36.dp)) {
                    Icon(Icons.Default.Download, null, Modifier.size(17.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("同步歌单", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
internal fun PlaylistCover(playlist: PlaylistSummary, modifier: Modifier) {
    CachedCoverImage(
        url = playlist.coverUri?.toString(),
        contentDescription = "${playlist.name} 封面",
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
    )
}

@Composable
internal fun PlaylistDetailScreen(detail: PlaylistDetail, message: String, matchFlags: Map<String, Boolean>, model: MainViewModel, onBack: () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
                Text(detail.summary.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (detail.summary.source != Track.Source.LOCAL) {
                    IconButton(onClick = { model.syncPlaylistToLocal(detail.summary) }) { Icon(Icons.Default.Refresh, "同步歌单数据，不下载音频") }
                }
                IconButton(onClick = { model.openPlaylist(detail.summary, force = true) }) { Icon(Icons.Default.Refresh, "刷新歌单") }
            }
            Card(
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(14.dp)) {
                    PlaylistCover(detail.summary, Modifier.size(112.dp))
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(detail.summary.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(listOfNotNull(detail.summary.creator.takeIf(String::isNotBlank), "${detail.tracks.size} 首歌曲").joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (detail.summary.source != Track.Source.LOCAL) Text("线上歌单 · 可同步为本地副本", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            val missingLocalCount = detail.tracks.count { track ->
                track.source != Track.Source.LOCAL && track.source != Track.Source.DOWNLOADED && matchFlags[track.id] != true
            }
            FilledTonalButton(
                onClick = { model.enqueueMissingPlaylistTracks(detail.tracks) },
                enabled = missingLocalCount > 0,
                modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
            ) {
                Icon(Icons.Default.Download, null, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (missingLocalCount > 0) "下载未本地曲目（$missingLocalCount）" else "所有曲目均已在本地可用")
            }
            if (message.isNotBlank()) {
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 10.dp),
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
            if (detail.tracks.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { Text("歌单暂无可播放曲目", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            } else {
                LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                    items(detail.tracks, key = Track::id) { track ->
                        TrackRow(
                            track = track,
                            matchedLocal = matchFlags[track.id] == true,
                            onClick = { model.playPlaylist(detail.tracks, track) },
                        )
                    }
                }
            }
        }
    }
}