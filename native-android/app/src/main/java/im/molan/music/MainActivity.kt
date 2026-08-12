package im.molan.music

import android.Manifest
import android.graphics.BitmapFactory
import android.os.Build
import android.util.Base64
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import im.molan.music.model.AppSettings
import im.molan.music.model.DownloadEntry
import im.molan.music.model.PlaybackMode
import im.molan.music.model.PlayerSnapshot
import im.molan.music.model.LyricLine
import im.molan.music.model.PlaylistDetail
import im.molan.music.model.PlaylistSummary
import im.molan.music.model.NcmQrLoginState
import im.molan.music.model.Track
import im.molan.music.ui.darkWineScheme
import im.molan.music.ui.lightWineScheme

class MainActivity : ComponentActivity() {
    private val model: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { QingyinApp(model) }
    }
}

private enum class AppTab(val title: String) { HOME("首页"), SEARCH("搜索"), PLAYLISTS("歌单"), LOCAL("本地"), DOWNLOADS("下载"), MINE("我的") }

@Composable
private fun QingyinApp(model: MainViewModel = viewModel()) {
    val settings by model.settings.collectAsStateWithLifecycle()
    val tracks by model.localTracks.collectAsStateWithLifecycle()
    val scanMessage by model.scanMessage.collectAsStateWithLifecycle()
    val player by model.playback.snapshot.collectAsStateWithLifecycle()
    val playbackError by model.playback.errorMessage.collectAsStateWithLifecycle()
    val searchTracks by model.searchTracks.collectAsStateWithLifecycle()
    val dailyTracks by model.dailyTracks.collectAsStateWithLifecycle()
    val dailyMessage by model.dailyMessage.collectAsStateWithLifecycle()
    val networkMessage by model.networkMessage.collectAsStateWithLifecycle()
    val lyrics by model.lyrics.collectAsStateWithLifecycle()
    val ncmQrLogin by model.ncmQrLogin.collectAsStateWithLifecycle()
    val myPlaylists by model.myPlaylists.collectAsStateWithLifecycle()
    val importedPlaylists by model.importedPlaylists.collectAsStateWithLifecycle()
    val localPlaylists by model.localPlaylists.collectAsStateWithLifecycle()
    val downloads by model.downloads.collectAsStateWithLifecycle()
    val downloadedTracks by model.downloadedTracks.collectAsStateWithLifecycle()
    val downloadMessage by model.downloadMessage.collectAsStateWithLifecycle()
    val downloadActionMessage by model.downloadActionMessage.collectAsStateWithLifecycle()
    val playlistDetail by model.playlistDetail.collectAsStateWithLifecycle()
    val playlistMessage by model.playlistMessage.collectAsStateWithLifecycle()
    var tab by remember { mutableStateOf(AppTab.HOME) }
    var queueVisible by remember { mutableStateOf(false) }
    var playerVisible by remember { mutableStateOf(false) }
    var ncmLoginVisible by remember { mutableStateOf(false) }
    var settingsVisible by remember { mutableStateOf(false) }
    var donateVisible by remember { mutableStateOf(false) }
    var ncmAccountVisible by remember { mutableStateOf(false) }
    var playlistImportSource by remember { mutableStateOf<Track.Source?>(null) }
    var localPlaylistCreateVisible by remember { mutableStateOf(false) }
    val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
    val mediaPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) { model.scanLocalMusic(); model.refreshDownloads() }
    }
    val customFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let(model::scanCustomFolder)
    }

    BackHandler(enabled = playlistDetail != null || queueVisible || playerVisible || ncmLoginVisible || settingsVisible || donateVisible || ncmAccountVisible || playlistImportSource != null || localPlaylistCreateVisible || tab != AppTab.HOME) {
        when {
            playlistDetail != null -> model.closePlaylist()
            queueVisible -> queueVisible = false
            playerVisible -> playerVisible = false
            ncmLoginVisible -> { ncmLoginVisible = false; model.cancelNcmQrLogin() }
            settingsVisible -> settingsVisible = false
            donateVisible -> donateVisible = false
            ncmAccountVisible -> ncmAccountVisible = false
            playlistImportSource != null -> playlistImportSource = null
            localPlaylistCreateVisible -> localPlaylistCreateVisible = false
            else -> tab = AppTab.HOME
        }
    }

    LaunchedEffect(Unit) { model.scanLocalMusic(); model.refreshDownloads() }
    LaunchedEffect(settings.importedPlaylistIds) { model.loadImportedPlaylists() }
    LaunchedEffect(settings.customFolderUris) { model.restoreCustomFolders(settings.customFolderUris) }
    LaunchedEffect(settings.ncmCookie, settings.useBackupNcmc) { model.loadDaily() }
    LaunchedEffect(settings.ncmCookie, settings.ncmUserId, settings.useBackupNcmc) { model.loadMyPlaylists() }

    MaterialTheme(colorScheme = if (settings.darkTheme) darkWineScheme() else lightWineScheme()) {
        Scaffold(
            contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
            bottomBar = {
                Column {
                    if (player.current != null) MiniPlayer(player, model, onQueue = { queueVisible = true }, onOpen = { playerVisible = true })
                    NavigationBar {
                        listOf(AppTab.HOME, AppTab.SEARCH, AppTab.PLAYLISTS, AppTab.LOCAL, AppTab.DOWNLOADS, AppTab.MINE).forEach { item ->
                            val icon = when (item) { AppTab.HOME -> Icons.Default.Home; AppTab.SEARCH -> Icons.Default.Search; AppTab.PLAYLISTS -> Icons.Default.LibraryMusic; AppTab.LOCAL -> Icons.Default.LibraryMusic; AppTab.DOWNLOADS -> Icons.Default.Download; AppTab.MINE -> Icons.Default.AccountCircle }
                            NavigationBarItem(selected = tab == item, onClick = { tab = item }, icon = { Icon(icon, item.title) }, label = { Text(item.title) })
                        }
                    }
                }
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding).statusBarsPadding()) {
                if (playlistDetail != null) {
                    PlaylistDetailScreen(
                        detail = playlistDetail!!,
                        model = model,
                        onBack = model::closePlaylist,
                    )
                } else {
                    when (tab) {
                        AppTab.HOME -> HomeScreen(tracks, dailyTracks, dailyMessage, model)
                        AppTab.SEARCH -> SearchScreen(searchTracks, networkMessage, playbackError, model)
                        AppTab.PLAYLISTS -> PlaylistHubScreen((myPlaylists + importedPlaylists + localPlaylists).distinctBy { it.id }, playlistMessage, model, onCreateLocal = { localPlaylistCreateVisible = true })
                        AppTab.LOCAL -> LocalScreen(tracks, settings.customFolderUris, scanMessage, model, onRequestPermission = { mediaPermission.launch(permission) }, onPickFolder = { customFolder.launch(null) })
                        AppTab.DOWNLOADS -> DownloadsScreen(downloads, downloadedTracks, downloadMessage, model)
                        AppTab.MINE -> MineScreen(settings, model, onNcmLogin = { ncmLoginVisible = true; model.startNcmQrLogin() }, onNcmAccount = { ncmAccountVisible = true }, onImportPlaylist = { playlistImportSource = it }, onSettings = { settingsVisible = true }, onDonate = { donateVisible = true })
                    }
                }
                if (queueVisible) {
                    QueueDialog(
                        snapshot = player,
                        model = model,
                        modifier = Modifier.align(Alignment.BottomCenter),
                        onDismiss = { queueVisible = false },
                    )
                }
            }
        }
        if (playerVisible) FullPlayerDialog(player, lyrics, playbackError, downloadActionMessage, model, onDismiss = { playerVisible = false })
        if (ncmLoginVisible) NcmQrLoginDialog(ncmQrLogin, onDismiss = { ncmLoginVisible = false; model.cancelNcmQrLogin() }, onRefresh = model::startNcmQrLogin)
        if (settingsVisible) SettingsDialog(settings, onDismiss = { settingsVisible = false }, onSave = { next -> model.updateSettings { next }; settingsVisible = false })
        if (donateVisible) DonateDialog(onDismiss = { donateVisible = false })
        if (ncmAccountVisible) NcmAccountDialog(settings, onDismiss = { ncmAccountVisible = false }, onLogout = { model.logoutNcm(); ncmAccountVisible = false })
        if (localPlaylistCreateVisible) CreateLocalPlaylistDialog(onDismiss = { localPlaylistCreateVisible = false }, onCreate = { name -> model.createLocalPlaylist(name); localPlaylistCreateVisible = false })
        playlistImportSource?.let { source -> PlaylistImportDialog(source, onDismiss = { playlistImportSource = null }, onImport = { input -> model.importPlaylist(source, input); playlistImportSource = null }) }
    }
}

@Composable
private fun HomeScreen(tracks: List<Track>, dailyTracks: List<Track>, dailyMessage: String, model: MainViewModel) {
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), shape = RoundedCornerShape(24.dp)) {
                Column(Modifier.padding(22.dp)) {
                    Text("音乐，刚刚好", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                }
            }
        }
        item { Text("快捷入口", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                QuickCard("本地音乐", "${tracks.size} 首", Icons.Default.LibraryMusic, Modifier.weight(1f))
                QuickCard("播放队列", "原生管理", Icons.Default.QueueMusic, Modifier.weight(1f))
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("每日推荐", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                IconButton(onClick = { model.loadDaily(force = true) }) { Icon(Icons.Default.Refresh, "刷新每日推荐") }
            }
        }
        if (dailyMessage.isNotBlank()) item { Text(dailyMessage, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (dailyTracks.isNotEmpty()) items(dailyTracks.take(12), key = { "daily:${it.id}" }) { track -> TrackRow(track, matchedLocal = model.hasLocalMatch(track), onClick = { model.playDaily(track) }) }
        item { Text("最近扫描", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp)) }
        if (tracks.isEmpty()) item { EmptyHint("尚未扫描本地音乐。请到“本地”页面授权并扫描。") }
        else items(tracks.take(8), key = Track::id) { TrackRow(it, onClick = { model.playLocal(it) }) }
    }
}

@Composable
private fun SearchScreen(searchTracks: List<Track>, message: String, playbackError: String, model: MainViewModel) {
    var query by remember { mutableStateOf("") }
    var source by remember { mutableStateOf(Track.Source.NETEASE) }
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("线上搜索", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { source = Track.Source.NETEASE }, modifier = Modifier.height(40.dp)) { Text(if (source == Track.Source.NETEASE) "网易云 ✓" else "网易云") }
                FilledTonalButton(onClick = { source = Track.Source.QQ }, modifier = Modifier.height(40.dp)) { Text(if (source == Track.Source.QQ) "QQ 音乐 ✓" else "QQ 音乐") }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextField(
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
                if (source == Track.Source.QQ) "QQ 使用 ChKSz API 与独立 QQ 音质档位；请先在设置中填写 API Key。" else "网易云使用 NCMC 与网易云独立音质档位。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item { Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (playbackError.isNotBlank()) item { Text(playbackError, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        if (searchTracks.isEmpty()) item { EmptyHint("选择来源并输入关键词后搜索在线音乐") }
        else items(searchTracks, key = Track::id) { track -> TrackRow(track, matchedLocal = model.hasLocalMatch(track), onClick = { model.playOnline(track) }) }
    }
}

@Composable
private fun QuickCard(title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier = Modifier) {
    Card(modifier, shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(14.dp)) {
            Icon(icon, title, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(12.dp))
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun LocalScreen(tracks: List<Track>, folderUris: List<String>, message: String, model: MainViewModel, onRequestPermission: () -> Unit, onPickFolder: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) { Text("本地音乐", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold); Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
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
private fun DownloadsScreen(entries: List<DownloadEntry>, tracks: List<Track>, message: String, model: MainViewModel) {
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text("下载", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("由轻音内置下载器管理，最多同时下载 3 首", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = model::refreshDownloads, modifier = Modifier.size(44.dp)) { Icon(Icons.Default.Refresh, "刷新下载列表") }
            }
        }
        item { Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (entries.isNotEmpty()) {
            item { Text("内置下载任务", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
            items(entries, key = DownloadEntry::id) { entry -> DownloadTaskRow(entry, onRetry = { model.retryDownload(entry.id) }) }
        }
        item { Text("已下载音乐", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp)) }
        if (tracks.isEmpty()) item { EmptyHint("尚未发现已完成的音乐。下载由轻音内置队列直接写入应用下载目录。") }
        else items(tracks, key = Track::id) { track -> TrackRow(track, onClick = { model.playDownloaded(track) }) }
    }
}

@Composable
private fun DownloadTaskRow(entry: DownloadEntry, onRetry: () -> Unit) {
    val progress = if (entry.totalBytes > 0L) (entry.bytesDownloaded.toFloat() / entry.totalBytes.toFloat()).coerceIn(0f, 1f) else 0f
    Card(shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(entry.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(entry.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (entry.status == DownloadEntry.Status.FAILED) {
                    TextButton(onClick = onRetry) { Text("重试") }
                } else {
                    Text(downloadStatusLabel(entry.status), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
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
private fun PlaylistHubScreen(playlists: List<PlaylistSummary>, playlistMessage: String, model: MainViewModel, onCreateLocal: () -> Unit) {
    val online = playlists.filter { it.source != Track.Source.LOCAL }
    val local = playlists.filter { it.source == Track.Source.LOCAL }
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) { Text("歌单", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold); Text("线上同步与本地自建歌单", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
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
private fun MineScreen(settings: AppSettings, model: MainViewModel, onNcmLogin: () -> Unit, onNcmAccount: () -> Unit, onImportPlaylist: (Track.Source) -> Unit, onSettings: () -> Unit, onDonate: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
private fun CreateLocalPlaylistDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
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
private fun PlaylistCard(playlist: PlaylistSummary, modifier: Modifier, onClick: () -> Unit, onRefresh: () -> Unit, onSync: (() -> Unit)? = null) {
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
private fun PlaylistRow(playlist: PlaylistSummary, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlaylistCover(playlist, Modifier.size(54.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(playlist.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
            Text(listOfNotNull(playlist.creator.takeIf(String::isNotBlank), playlist.trackCount.takeIf { it > 0 }?.let { "$it 首" }).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = onClick, modifier = Modifier.size(46.dp)) { Icon(Icons.Default.MoreVert, "打开歌单", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun PlaylistCover(playlist: PlaylistSummary, modifier: Modifier) {
    val context = LocalContext.current
    if (playlist.coverUri == null) {
        Box(modifier.clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) { Icon(Icons.Default.LibraryMusic, null, tint = MaterialTheme.colorScheme.primary) }
    } else {
        AsyncImage(
            model = ImageRequest.Builder(context).data(playlist.coverUri).size(360).memoryCachePolicy(CachePolicy.ENABLED).diskCachePolicy(CachePolicy.ENABLED).crossfade(true).build(),
            contentDescription = "${playlist.name} 封面",
            modifier = modifier.clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.secondaryContainer),
        )
    }
}

@Composable
private fun PlaylistDetailScreen(detail: PlaylistDetail, model: MainViewModel, onBack: () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "返回") }
                Text(detail.summary.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (detail.summary.source != Track.Source.LOCAL) {
                    IconButton(onClick = { model.syncPlaylistToLocal(detail.summary) }) { Icon(Icons.Default.Refresh, "同步歌单数据，不下载音频") }
                }
                IconButton(onClick = { model.openPlaylist(detail.summary, force = true) }) { Icon(Icons.Default.Refresh, "刷新歌单") }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 12.dp)) {
                PlaylistCover(detail.summary, Modifier.size(116.dp))
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(detail.summary.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(6.dp))
                    Text(listOfNotNull(detail.summary.creator.takeIf(String::isNotBlank), "${detail.tracks.size} 首歌曲").joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            HorizontalDivider()
            if (detail.tracks.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { Text("歌单暂无可播放曲目", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            } else {
                LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                    items(detail.tracks, key = Track::id) { track ->
                        TrackRow(
                            track = track,
                            matchedLocal = model.hasLocalMatch(track),
                            onClick = { model.playOnline(track) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaylistImportDialog(source: Track.Source, onDismiss: () -> Unit, onImport: (String) -> Unit) {
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
private fun NcmAccountDialog(settings: AppSettings, onDismiss: () -> Unit, onLogout: () -> Unit) {
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
private fun DonateDialog(onDismiss: () -> Unit) {
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
private fun SettingsDialog(settings: AppSettings, onDismiss: () -> Unit, onSave: (AppSettings) -> Unit) {
    var primaryNcm by remember(settings) { mutableStateOf(settings.ncmcBaseUrl) }
    var backupNcm by remember(settings) { mutableStateOf(settings.backupNcmcBaseUrl) }
    var useBackupNcm by remember(settings) { mutableStateOf(settings.useBackupNcmc) }
    var chkszBase by remember(settings) { mutableStateOf(settings.chkszBaseUrl) }
    var apiKey by remember(settings) { mutableStateOf(settings.chkszApiKey) }
    var quality by remember(settings) { mutableStateOf(settings.quality) }
    var qqQuality by remember(settings) { mutableStateOf(settings.qqQuality) }
    var darkTheme by remember(settings) { mutableStateOf(settings.darkTheme) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("设置") },
        text = {
            LazyColumn(Modifier.height(440.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item { SettingRow("深色模式", "跟随你的界面偏好", Icons.Default.DarkMode, darkTheme) { darkTheme = !darkTheme } }
                item { Text("播放与下载", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold) }
                item { Text("网易云音质", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold) }
                items(AppSettings.Quality.entries) { option ->
                    Card(
                        Modifier.fillMaxWidth().clickable { quality = option },
                        colors = CardDefaults.cardColors(containerColor = if (quality == option) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant),
                    ) {
                        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(option.label, Modifier.weight(1f))
                            if (quality == option) Text("已选", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                item { Text("QQ 音质", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp)) }
                items(AppSettings.QqQuality.entries) { option ->
                    Card(
                        Modifier.fillMaxWidth().clickable { qqQuality = option },
                        colors = CardDefaults.cardColors(containerColor = if (qqQuality == option) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant),
                    ) {
                        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(option.label, Modifier.weight(1f))
                            if (qqQuality == option) Text("已选", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                item { Text("网易云私有服务", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp)) }
                item { TextField(primaryNcm, { primaryNcm = it }, modifier = Modifier.fillMaxWidth(), label = { Text("主 NCMC 地址") }, singleLine = true) }
                item { TextField(backupNcm, { backupNcm = it }, modifier = Modifier.fillMaxWidth(), label = { Text("备用 NCMC 地址（可选）") }, singleLine = true) }
                item { SettingRow("使用备用 NCMC", "主线路异常时可手动切换", Icons.Default.Refresh, useBackupNcm) { useBackupNcm = !useBackupNcm } }
                item { Text("QQ / ChKSz API", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp)) }
                item { TextField(chkszBase, { chkszBase = it }, modifier = Modifier.fillMaxWidth(), label = { Text("ChKSz 主地址") }, singleLine = true) }
                item { TextField(apiKey, { apiKey = it }, modifier = Modifier.fillMaxWidth(), label = { Text("ChKSz API Key（QQ 搜索必填）") }, singleLine = true) }
                item { Text("QQ 搜索和受限网易云曲目将优先使用官方 ChKSz 主线路；旧备用域名已停用以避免 404。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        },
        confirmButton = {
            FilledTonalButton(onClick = {
                onSave(settings.copy(
                    darkTheme = darkTheme,
                    quality = quality,
                    qqQuality = qqQuality,
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

@Composable
private fun SettingRow(title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector, enabled: Boolean, onToggle: () -> Unit) {
    Card(shape = RoundedCornerShape(18.dp)) { Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) { Icon(icon, null, tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) { Text(title, fontWeight = FontWeight.SemiBold); Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }; Switch(checked = enabled, onCheckedChange = { onToggle() }) } }
}

@Composable
private fun CoverArt(track: Track, modifier: Modifier, shape: androidx.compose.ui.graphics.Shape) {
    val context = LocalContext.current
    val source = track.artworkUri ?: track.uri
    if (source == null) {
        Box(modifier.clip(shape).background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
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
                .build(),
            contentDescription = "${track.title} 封面",
            modifier = modifier.clip(shape).background(MaterialTheme.colorScheme.secondaryContainer),
        )
    }
}

@Composable
private fun TrackRow(track: Track, matchedLocal: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(vertical = 12.dp, horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoverArt(track, Modifier.size(58.dp), RoundedCornerShape(12.dp))
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
    }
}

@Composable
private fun MiniPlayer(snapshot: PlayerSnapshot, model: MainViewModel, onQueue: () -> Unit, onOpen: () -> Unit) {
    val current = snapshot.current ?: return
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHighest).padding(horizontal = 12.dp, vertical = 8.dp)) {
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
            IconButton(onClick = onQueue, modifier = Modifier.size(48.dp)) { Icon(Icons.Default.QueueMusic, "播放队列", Modifier.size(25.dp)) }
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
private fun QueueDialog(
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
private fun NcmQrLoginDialog(state: NcmQrLoginState, onDismiss: () -> Unit, onRefresh: () -> Unit) {
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
private fun FullPlayerDialog(snapshot: PlayerSnapshot, lyrics: List<LyricLine>, playbackError: String, downloadActionMessage: String, model: MainViewModel, onDismiss: () -> Unit) {
    val current = snapshot.current ?: return
    val duration = maxOf(snapshot.durationMs, current.durationMs, 1L)
    val activeLine = lyrics.indexOfLast { it.timeMs <= snapshot.positionMs + 80 }
    val pagerState = rememberPagerState(pageCount = { 2 })
    val lyricListState = rememberLazyListState()
    val density = LocalDensity.current
    var sliderValue by remember(current.id) { mutableStateOf(0f) }
    var isSeeking by remember(current.id) { mutableStateOf(false) }

    LaunchedEffect(snapshot.positionMs, duration, isSeeking) {
        if (!isSeeking) sliderValue = (snapshot.positionMs.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
    }
    LaunchedEffect(activeLine, pagerState.currentPage, lyrics.size) {
        if (pagerState.currentPage == 1 && activeLine >= 0) {
            // 等待歌词页完成布局，再把当前行平滑置于可视区域中心。
            delay(48)
            val viewport = lyricListState.layoutInfo.viewportEndOffset - lyricListState.layoutInfo.viewportStartOffset
            // 将歌词行自身的中心（而非行顶部）对齐到屏幕中心。
            val lineHalfHeight = with(density) { 34.dp.roundToPx() }
            lyricListState.animateScrollToItem(activeLine, if (viewport > 0) -viewport / 2 + lineHalfHeight else 0)
        }
    }

    Box(Modifier.fillMaxSize()) {
        CoverBackdrop(current)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.26f), MaterialTheme.colorScheme.background.copy(alpha = 0.88f)))))
        Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 18.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) { Icon(Icons.Default.ArrowBack, "返回") }
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
                    LazyColumn(
                        state = lyricListState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 180.dp, horizontal = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp),
                    ) {
                        items(lyrics, key = LyricLine::timeMs) { line ->
                            val index = lyrics.indexOf(line)
                            Column {
                                Text(
                                    line.text,
                                    style = if (index == activeLine) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleLarge,
                                    color = if (index == activeLine) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.64f),
                                    fontWeight = if (index == activeLine) FontWeight.ExtraBold else FontWeight.Normal,
                                )
                                line.translation?.let { translated ->
                                    Text(translated, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 5.dp))
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
private fun CoverBackdrop(track: Track) {
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
private fun EmptyHint(text: String) = Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) { Text(text, Modifier.padding(18.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }

private fun formatDuration(milliseconds: Long): String {
    val seconds = (milliseconds / 1000).coerceAtLeast(0)
    return "%d:%02d".format(seconds / 60, seconds % 60)
}

private fun modeLabel(mode: PlaybackMode) = when (mode) { PlaybackMode.LOOP -> "列表循环"; PlaybackMode.SINGLE -> "单曲循环"; PlaybackMode.SHUFFLE -> "随机播放" }

private fun downloadStatusLabel(status: DownloadEntry.Status) = when (status) {
    DownloadEntry.Status.QUEUED -> "等待中"
    DownloadEntry.Status.DOWNLOADING -> "下载中"
    DownloadEntry.Status.PAUSED -> "已暂停"
    DownloadEntry.Status.COMPLETED -> "已完成"
    DownloadEntry.Status.FAILED -> "下载失败"
    DownloadEntry.Status.MISSING -> "文件缺失"
}

private fun formatBytes(bytes: Long): String = when {
    bytes < 1_024L -> "$bytes B"
    bytes < 1_024L * 1_024L -> "%.1f KB".format(bytes / 1_024f)
    bytes < 1_024L * 1_024L * 1_024L -> "%.1f MB".format(bytes / (1_024f * 1_024f))
    else -> "%.2f GB".format(bytes / (1_024f * 1_024f * 1_024f))
}
