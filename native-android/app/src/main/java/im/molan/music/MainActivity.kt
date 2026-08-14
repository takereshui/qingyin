package im.molan.music

import android.Manifest
import android.app.Activity
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import im.molan.music.model.Track
import im.molan.music.ui.darkWineScheme
import im.molan.music.ui.lightWineScheme
import im.molan.music.ui.qingyinShapes
import im.molan.music.ui.qingyinTypography

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
    val matchFlags by model.localMatchFlags.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(AppTab.HOME) }
    var queueVisible by rememberSaveable { mutableStateOf(false) }
    var playerVisible by rememberSaveable { mutableStateOf(false) }
    var ncmLoginVisible by rememberSaveable { mutableStateOf(false) }
    var settingsVisible by rememberSaveable { mutableStateOf(false) }
    var donateVisible by rememberSaveable { mutableStateOf(false) }
    var ncmAccountVisible by rememberSaveable { mutableStateOf(false) }
    var playlistImportSource by rememberSaveable { mutableStateOf<Track.Source?>(null) }
    var localPlaylistCreateVisible by rememberSaveable { mutableStateOf(false) }
    val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
    val mediaPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) { model.scanLocalMusic(); model.refreshDownloads() }
    }
    val notificationsPermission =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.POST_NOTIFICATIONS else null
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(notificationsPermission) {
        notificationsPermission?.let { notificationLauncher.launch(it) }
    }
    val customFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let(model::scanCustomFolder)
    }

    val downloadFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let(model::setDownloadFolder)
    }

    val overlayOpen = queueVisible || playerVisible || ncmLoginVisible || settingsVisible ||
        donateVisible || ncmAccountVisible || playlistImportSource != null || localPlaylistCreateVisible
    val activity = LocalContext.current as? Activity
    val atRoot = !overlayOpen && playlistDetail == null && tab == AppTab.HOME
    // 非根页返回：最上层是播放器/队列先关，其次是歌单详情，最后回首页。
    BackHandler(enabled = !atRoot) {
        when {
            playerVisible -> playerVisible = false
            queueVisible -> queueVisible = false
            playlistDetail != null -> model.closePlaylist()
            ncmLoginVisible -> { ncmLoginVisible = false; model.cancelNcmQrLogin() }
            settingsVisible -> settingsVisible = false
            donateVisible -> donateVisible = false
            ncmAccountVisible -> ncmAccountVisible = false
            playlistImportSource != null -> playlistImportSource = null
            localPlaylistCreateVisible -> localPlaylistCreateVisible = false
            else -> tab = AppTab.HOME
        }
    }
    // 根页返回：不直接退出应用，仅退回桌面（音乐仍在后台播放，再次打开秒回）。
    BackHandler(enabled = atRoot) {
        activity?.moveTaskToBack(true)
    }

    LaunchedEffect(Unit) { model.scanLocalMusic(); model.refreshDownloads() }
    LaunchedEffect(settings.importedPlaylistIds) { model.loadImportedPlaylists() }
    LaunchedEffect(settings.customFolderUris) { model.restoreCustomFolders(settings.customFolderUris) }
    LaunchedEffect(settings.ncmCookie, settings.useBackupNcmc) { model.loadDaily() }
    LaunchedEffect(settings.ncmCookie, settings.ncmUserId, settings.useBackupNcmc) { model.loadMyPlaylists() }

    MaterialTheme(
        colorScheme = if (settings.darkTheme) darkWineScheme() else lightWineScheme(),
        typography = qingyinTypography,
        shapes = qingyinShapes,
    ) {
        Scaffold(
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
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
                        message = playlistMessage,
                        matchFlags = matchFlags,
                        model = model,
                        onBack = model::closePlaylist,
                    )
                } else {
                    when (tab) {
                        AppTab.HOME -> HomeScreen(
                            tracks = tracks,
                            dailyTracks = dailyTracks,
                            dailyMessage = dailyMessage,
                            matchFlags = matchFlags,
                            model = model,
                            onOpenLocal = { tab = AppTab.LOCAL },
                            onOpenQueue = { queueVisible = true },
                        )
                        AppTab.SEARCH -> SearchScreen(searchTracks, networkMessage, playbackError, matchFlags, model)
                        AppTab.PLAYLISTS -> PlaylistHubScreen((myPlaylists + importedPlaylists + localPlaylists).distinctBy { it.id }, playlistMessage, model, onCreateLocal = { localPlaylistCreateVisible = true })
                        AppTab.LOCAL -> LocalScreen(tracks, settings.customFolderUris, scanMessage, model, onRequestPermission = { mediaPermission.launch(permission) }, onPickFolder = { customFolder.launch(null) })
                        AppTab.DOWNLOADS -> DownloadsScreen(downloads, downloadedTracks, settings.downloadFolderUri, downloadMessage, model, onPickFolder = { downloadFolder.launch(null) }, onClearFolder = model::clearDownloadFolder)
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
        if (settingsVisible) SettingsDialog(settings, cacheSpaceBytes = model.onlineCacheSpace, onDismiss = { settingsVisible = false }, onSave = { next -> model.updateSettings { next }; settingsVisible = false }, onClearCache = model::clearOnlineCache)
        if (donateVisible) DonateDialog(onDismiss = { donateVisible = false })
        if (ncmAccountVisible) NcmAccountDialog(settings, onDismiss = { ncmAccountVisible = false }, onLogout = { model.logoutNcm(); ncmAccountVisible = false })
        if (localPlaylistCreateVisible) CreateLocalPlaylistDialog(onDismiss = { localPlaylistCreateVisible = false }, onCreate = { name -> model.createLocalPlaylist(name); localPlaylistCreateVisible = false })
        playlistImportSource?.let { source -> PlaylistImportDialog(source, onDismiss = { playlistImportSource = null }, onImport = { input -> model.importPlaylist(source, input); playlistImportSource = null }) }
    }
}