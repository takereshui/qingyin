package im.molan.music

import android.Manifest
import android.graphics.BitmapFactory
import android.os.Build
import android.util.Base64
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.DarkMode
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
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import im.molan.music.model.AppSettings
import im.molan.music.model.PlaybackMode
import im.molan.music.model.PlayerSnapshot
import im.molan.music.model.LyricLine
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

private enum class AppTab(val title: String) { HOME("首页"), LOCAL("本地"), DOWNLOADS("下载"), MINE("我的") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QingyinApp(model: MainViewModel = viewModel()) {
    val settings by model.settings.collectAsStateWithLifecycle()
    val tracks by model.localTracks.collectAsStateWithLifecycle()
    val scanMessage by model.scanMessage.collectAsStateWithLifecycle()
    val player by model.playback.snapshot.collectAsStateWithLifecycle()
    val searchTracks by model.searchTracks.collectAsStateWithLifecycle()
    val dailyTracks by model.dailyTracks.collectAsStateWithLifecycle()
    val dailyMessage by model.dailyMessage.collectAsStateWithLifecycle()
    val networkMessage by model.networkMessage.collectAsStateWithLifecycle()
    val lyrics by model.lyrics.collectAsStateWithLifecycle()
    val ncmQrLogin by model.ncmQrLogin.collectAsStateWithLifecycle()
    var tab by remember { mutableStateOf(AppTab.HOME) }
    var queueVisible by remember { mutableStateOf(false) }
    var playerVisible by remember { mutableStateOf(false) }
    var ncmLoginVisible by remember { mutableStateOf(false) }
    var settingsVisible by remember { mutableStateOf(false) }
    var donateVisible by remember { mutableStateOf(false) }
    val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
    val mediaPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) model.scanLocalMusic()
    }
    val customFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let(model::scanCustomFolder)
    }

    LaunchedEffect(Unit) { if (model.hasMediaPermission()) model.scanLocalMusic() }
    LaunchedEffect(settings.customFolderUri) { model.restoreCustomFolder(settings.customFolderUri) }
    LaunchedEffect(settings.ncmCookie, settings.useBackupNcmc) { model.loadDaily() }

    MaterialTheme(colorScheme = if (settings.darkTheme) darkWineScheme() else lightWineScheme()) {
        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    title = { Text("轻音", fontWeight = FontWeight.Bold) },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = MaterialTheme.colorScheme.primary, titleContentColor = MaterialTheme.colorScheme.onPrimary),
                )
            },
            bottomBar = {
                Column {
                    if (player.current != null) MiniPlayer(player, model, onQueue = { queueVisible = true }, onOpen = { playerVisible = true })
                    NavigationBar {
                        listOf(AppTab.HOME, AppTab.LOCAL, AppTab.DOWNLOADS, AppTab.MINE).forEach { item ->
                            val icon = when (item) { AppTab.HOME -> Icons.Default.Home; AppTab.LOCAL -> Icons.Default.LibraryMusic; AppTab.DOWNLOADS -> Icons.Default.Download; AppTab.MINE -> Icons.Default.AccountCircle }
                            NavigationBarItem(selected = tab == item, onClick = { tab = item }, icon = { Icon(icon, item.title) }, label = { Text(item.title) })
                        }
                    }
                }
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                when (tab) {
                    AppTab.HOME -> HomeScreen(tracks, dailyTracks, dailyMessage, searchTracks, networkMessage, model)
                    AppTab.LOCAL -> LocalScreen(tracks, scanMessage, model, onRequestPermission = { mediaPermission.launch(permission) }, onPickFolder = { customFolder.launch(null) })
                    AppTab.DOWNLOADS -> DownloadsScreen()
                    AppTab.MINE -> MineScreen(settings, model, onNcmLogin = { ncmLoginVisible = true; model.startNcmQrLogin() }, onSettings = { settingsVisible = true }, onDonate = { donateVisible = true })
                }
            }
        }
        if (queueVisible) QueueDialog(player, model, onDismiss = { queueVisible = false })
        if (playerVisible) FullPlayerDialog(player, lyrics, model, onDismiss = { playerVisible = false })
        if (ncmLoginVisible) NcmQrLoginDialog(ncmQrLogin, onDismiss = { ncmLoginVisible = false; model.cancelNcmQrLogin() }, onRefresh = model::startNcmQrLogin)
        if (settingsVisible) SettingsDialog(settings, onDismiss = { settingsVisible = false }, onSave = { next -> model.updateSettings { next }; settingsVisible = false })
        if (donateVisible) DonateDialog(onDismiss = { donateVisible = false })
    }
}

@Composable
private fun HomeScreen(tracks: List<Track>, dailyTracks: List<Track>, dailyMessage: String, searchTracks: List<Track>, networkMessage: String, model: MainViewModel) {
    var query by remember { mutableStateOf("") }
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), shape = RoundedCornerShape(24.dp)) {
                Column(Modifier.padding(22.dp)) {
                    Text("轻松聆听", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(6.dp))
                    Text("音乐，刚刚好", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text("无广告、无打扰，专注每一首喜欢的歌。")
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
        if (dailyTracks.isNotEmpty()) items(dailyTracks.take(12), key = { "daily:${it.id}" }) { TrackRow(it, onClick = { model.playNcm(it) }) }
        item { Text("网易云搜索", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp)) }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextField(query, onValueChange = { query = it }, modifier = Modifier.weight(1f), singleLine = true, label = { Text("歌曲、歌手或专辑") })
                Spacer(Modifier.width(8.dp))
                FilledTonalButton(onClick = { model.searchNcm(query) }) { Text("搜索") }
            }
        }
        item { Text(networkMessage, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (searchTracks.isNotEmpty()) items(searchTracks, key = Track::id) { TrackRow(it, onClick = { model.playNcm(it) }) }
        item { Text("最近扫描", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp)) }
        if (tracks.isEmpty()) item { EmptyHint("尚未扫描本地音乐。请到“本地”页面授权并扫描。") }
        else items(tracks.take(8), key = Track::id) { TrackRow(it, onClick = { model.playLocal(it) }) }
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
private fun LocalScreen(tracks: List<Track>, message: String, model: MainViewModel, onRequestPermission: () -> Unit, onPickFolder: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) { Text("本地音乐", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold); Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilledTonalButton(onClick = onPickFolder) { Text("文件夹") }
                    FilledTonalButton(onClick = { if (model.hasMediaPermission()) model.scanLocalMusic() else onRequestPermission() }) { Icon(Icons.Default.Refresh, null); Spacer(Modifier.width(4.dp)); Text("扫描") }
                }
            }
        }
        if (tracks.isEmpty()) item { EmptyHint("轻音只扫描系统媒体库或你选择的文件夹，不导入或复制你的文件。") }
        else items(tracks, key = Track::id) { TrackRow(it, onClick = { model.playLocal(it) }) }
    }
}

@Composable
private fun DownloadsScreen() {
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(Icons.Default.Download, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(12.dp)); Text("系统下载", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp)); Text("新下载固定保存到 Music/轻音，完成后可在系统通知与文件管理器中查看。", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun MineScreen(settings: AppSettings, model: MainViewModel, onNcmLogin: () -> Unit, onSettings: () -> Unit, onDonate: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("我的", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
        item {
            Card(shape = RoundedCornerShape(18.dp)) { Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.AccountCircle, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp)); Column { Text("轻音", fontWeight = FontWeight.Bold); Text("纯净聆听，随心而动", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            } }
        }
        item { SettingRow("深色模式", "", Icons.Default.DarkMode, settings.darkTheme) { model.updateSettings { it.copy(darkTheme = !it.darkTheme) } } }
        item { FilledTonalButton(onClick = onSettings, modifier = Modifier.fillMaxWidth()) { Text("播放、下载与线路设置") } }
        item { Text("播放与下载音质", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
        items(AppSettings.Quality.entries) { quality ->
            Card(Modifier.fillMaxWidth().clickable { model.updateSettings { it.copy(quality = quality) } }, colors = CardDefaults.cardColors(containerColor = if (settings.quality == quality) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant)) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.MusicNote, null); Spacer(Modifier.width(10.dp)); Text(quality.label, Modifier.weight(1f)); if (settings.quality == quality) Text("已选", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold) }
            }
        }
        item {
            Card(shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text("网易云音乐", fontWeight = FontWeight.SemiBold)
                    Text(if (settings.ncmCookie.isBlank()) "未登录 · 可使用二维码登录同步账号能力" else "已登录${settings.ncmNickname.takeIf(String::isNotBlank)?.let { " · $it" } ?: ""}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(10.dp))
                    if (settings.ncmCookie.isBlank()) FilledTonalButton(onClick = onNcmLogin) { Text("二维码登录") }
                    else FilledTonalButton(onClick = model::logoutNcm) { Text("退出网易云登录") }
                }
            }
        }
        item { FilledTonalButton(onClick = onDonate, modifier = Modifier.fillMaxWidth()) { Text("赞赏支持") } }
    }
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
    var useChkszBackup by remember(settings) { mutableStateOf(settings.useChkszBackup) }
    var apiKey by remember(settings) { mutableStateOf(settings.chkszApiKey) }
    var quality by remember(settings) { mutableStateOf(settings.quality) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("播放、下载与线路设置") },
        text = {
            LazyColumn(Modifier.height(390.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item { Text("统一音质", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold) }
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
                item { Text("网易云私有服务", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp)) }
                item { TextField(primaryNcm, { primaryNcm = it }, modifier = Modifier.fillMaxWidth(), label = { Text("主 NCMC 地址") }, singleLine = true) }
                item { TextField(backupNcm, { backupNcm = it }, modifier = Modifier.fillMaxWidth(), label = { Text("备用 NCMC 地址（可选）") }, singleLine = true) }
                item { SettingRow("使用备用 NCMC", "主线路异常时可手动切换", Icons.Default.Refresh, useBackupNcm) { useBackupNcm = !useBackupNcm } }
                item { Text("ChKSz 备用线路", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp)) }
                item { TextField(chkszBase, { chkszBase = it }, modifier = Modifier.fillMaxWidth(), label = { Text("ChKSz 主地址") }, singleLine = true) }
                item { TextField(apiKey, { apiKey = it }, modifier = Modifier.fillMaxWidth(), label = { Text("ChKSz API Key（可选）") }, singleLine = true) }
                item { SettingRow("使用 ChKSz 备用域名", "切换至 api.chksz.top", Icons.Default.Refresh, useChkszBackup) { useChkszBackup = !useChkszBackup } }
            }
        },
        confirmButton = {
            FilledTonalButton(onClick = {
                onSave(settings.copy(
                    quality = quality,
                    ncmcBaseUrl = primaryNcm,
                    backupNcmcBaseUrl = backupNcm,
                    useBackupNcmc = useBackupNcm,
                    chkszBaseUrl = chkszBase,
                    useChkszBackup = useChkszBackup,
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
private fun TrackRow(track: Track, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(vertical = 10.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        CoverArt(track, Modifier.size(46.dp), RoundedCornerShape(10.dp))
        Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) { Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium); Text(listOf(track.artist, track.album, formatDuration(track.durationMs)).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Icon(Icons.Default.MoreVert, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun MiniPlayer(snapshot: PlayerSnapshot, model: MainViewModel, onQueue: () -> Unit, onOpen: () -> Unit) {
    val current = snapshot.current ?: return
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(horizontal = 10.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CoverArt(current, Modifier.size(44.dp), RoundedCornerShape(10.dp))
            Spacer(Modifier.width(10.dp)); Column(Modifier.weight(1f).clickable(onClick = onOpen)) { Text(current.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold); Text(current.artist, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            IconButton(onClick = model.playback::toggle) { Icon(if (snapshot.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, if (snapshot.isPlaying) "暂停" else "播放") }
            IconButton(onClick = model.playback::next) { Icon(Icons.Default.SkipNext, "下一首") }
            IconButton(onClick = onQueue) { Icon(Icons.Default.QueueMusic, "播放队列") }
        }
    }
}

@Composable
private fun QueueDialog(snapshot: PlayerSnapshot, model: MainViewModel, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("当前播放 · ${modeLabel(snapshot.playbackMode)}", Modifier.weight(1f))
                IconButton(onClick = model.playback::cycleMode) {
                    Icon(
                        if (snapshot.playbackMode == PlaybackMode.SHUFFLE) Icons.Default.Shuffle else Icons.Default.Repeat,
                        "切换播放模式",
                    )
                }
            }
        },
        text = {
            LazyColumn {
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
                            IconButton(onClick = { model.playback.removeAt(index) }) {
                                Text("×", style = MaterialTheme.typography.titleLarge)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { FilledTonalButton(onClick = model.playback::clearKeepingCurrent) { Text("清空其余歌曲") } },
        dismissButton = { FilledTonalButton(onClick = onDismiss) { Text("关闭") } },
    )
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
private fun FullPlayerDialog(snapshot: PlayerSnapshot, lyrics: List<LyricLine>, model: MainViewModel, onDismiss: () -> Unit) {
    val current = snapshot.current ?: return
    val duration = snapshot.durationMs.coerceAtLeast(1L)
    var pendingPosition by remember(current.id) { mutableStateOf(snapshot.positionMs.toFloat()) }
    LaunchedEffect(snapshot.positionMs) { pendingPosition = snapshot.positionMs.toFloat() }
    val activeLine = lyrics.indexOfLast { it.timeMs <= snapshot.positionMs + 80 }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                CoverArt(current, Modifier.size(156.dp), CircleShape)
                Spacer(Modifier.height(14.dp))
                Text(current.title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(current.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Slider(
                    value = pendingPosition.coerceIn(0f, duration.toFloat()),
                    onValueChange = { pendingPosition = it },
                    onValueChangeFinished = { model.playback.seekTo(pendingPosition.toLong()) },
                    valueRange = 0f..duration.toFloat(),
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(formatDuration(pendingPosition.toLong()), style = MaterialTheme.typography.labelSmall)
                    Text(formatDuration(snapshot.durationMs), style = MaterialTheme.typography.labelSmall)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
                    IconButton(onClick = model.playback::previous) { Icon(Icons.Default.SkipPrevious, "上一首") }
                    IconButton(onClick = model.playback::toggle, modifier = Modifier.size(58.dp)) { Icon(if (snapshot.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, if (snapshot.isPlaying) "暂停" else "播放", Modifier.size(38.dp)) }
                    IconButton(onClick = model.playback::next) { Icon(Icons.Default.SkipNext, "下一首") }
                    IconButton(onClick = model.playback::cycleMode) { Icon(if (snapshot.playbackMode == PlaybackMode.SHUFFLE) Icons.Default.Shuffle else Icons.Default.Repeat, modeLabel(snapshot.playbackMode)) }
                    if (current.remoteUrl != null) IconButton(onClick = { model.enqueueDownload(current) }) { Icon(Icons.Default.Download, "下载到 Music/轻音") }
                }
                HorizontalDivider(Modifier.padding(vertical = 10.dp))
                if (lyrics.isEmpty()) {
                    Text("暂无可用歌词", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    LazyColumn(Modifier.height(148.dp)) {
                        items(lyrics, key = LyricLine::timeMs) { line ->
                            val index = lyrics.indexOf(line)
                            Text(
                                line.text,
                                color = if (index == activeLine) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = if (index == activeLine) FontWeight.Bold else FontWeight.Normal,
                                modifier = Modifier.padding(vertical = 4.dp),
                            )
                            line.translation?.let { translated -> Text(translated, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    }
                }
            }
        },
        confirmButton = { FilledTonalButton(onClick = onDismiss) { Text("关闭") } },
    )
}

@Composable
private fun EmptyHint(text: String) = Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) { Text(text, Modifier.padding(18.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }

private fun formatDuration(milliseconds: Long): String {
    val seconds = (milliseconds / 1000).coerceAtLeast(0)
    return "%d:%02d".format(seconds / 60, seconds % 60)
}

private fun modeLabel(mode: PlaybackMode) = when (mode) { PlaybackMode.LOOP -> "列表循环"; PlaybackMode.SINGLE -> "单曲循环"; PlaybackMode.SHUFFLE -> "随机播放" }
