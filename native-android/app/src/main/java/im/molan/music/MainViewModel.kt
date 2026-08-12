package im.molan.music

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import im.molan.music.data.download.DownloadRepository
import im.molan.music.data.local.CustomFolderRepository
import im.molan.music.data.local.LocalMusicRepository
import im.molan.music.data.network.DailyRepository
import im.molan.music.data.network.NcmRepository
import im.molan.music.data.network.PlaylistRepository
import im.molan.music.data.network.QqRepository
import im.molan.music.data.settings.SettingsRepository
import im.molan.music.data.lyrics.LrcParser
import im.molan.music.data.lyrics.LyricsRepository
import im.molan.music.model.AppSettings
import im.molan.music.model.DownloadEntry
import im.molan.music.model.Track
import im.molan.music.model.LyricLine
import im.molan.music.model.NcmQrLoginState
import im.molan.music.model.PlaylistDetail
import im.molan.music.model.PlaylistSummary
import im.molan.music.playback.PlaybackConnection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val localRepository = LocalMusicRepository(application)
    private val customFolderRepository = CustomFolderRepository(application)
    private val downloadRepository = DownloadRepository(application)
    private val ncmRepository = NcmRepository()
    private val dailyRepository = DailyRepository(application, ncmRepository)
    private val playlistRepository = PlaylistRepository(application, ncmRepository)
    private val qqRepository = QqRepository()
    private val lyricsRepository = LyricsRepository(application, ncmRepository)
    private val settingsRepository = SettingsRepository(application)
    val playback = PlaybackConnection(application)

    private val _localTracks = MutableStateFlow<List<Track>>(emptyList())
    val localTracks: StateFlow<List<Track>> = _localTracks.asStateFlow()

    private val _scanMessage = MutableStateFlow("等待扫描本地音乐")
    val scanMessage: StateFlow<String> = _scanMessage.asStateFlow()
    private val _downloads = MutableStateFlow<List<DownloadEntry>>(emptyList())
    val downloads: StateFlow<List<DownloadEntry>> = _downloads.asStateFlow()
    private val _downloadedTracks = MutableStateFlow<List<Track>>(emptyList())
    val downloadedTracks: StateFlow<List<Track>> = _downloadedTracks.asStateFlow()
    private val _downloadMessage = MutableStateFlow("正在读取系统下载…")
    val downloadMessage: StateFlow<String> = _downloadMessage.asStateFlow()

    private val _searchTracks = MutableStateFlow<List<Track>>(emptyList())
    val searchTracks: StateFlow<List<Track>> = _searchTracks.asStateFlow()
    private val _dailyTracks = MutableStateFlow<List<Track>>(emptyList())
    val dailyTracks: StateFlow<List<Track>> = _dailyTracks.asStateFlow()
    private val _dailyMessage = MutableStateFlow("")
    val dailyMessage: StateFlow<String> = _dailyMessage.asStateFlow()
    private val _networkMessage = MutableStateFlow("可搜索网易云公开曲目")
    val networkMessage: StateFlow<String> = _networkMessage.asStateFlow()
    private val _lyrics = MutableStateFlow<List<LyricLine>>(emptyList())
    val lyrics: StateFlow<List<LyricLine>> = _lyrics.asStateFlow()
    private val _myPlaylists = MutableStateFlow<List<PlaylistSummary>>(emptyList())
    val myPlaylists: StateFlow<List<PlaylistSummary>> = _myPlaylists.asStateFlow()
    private val _importedPlaylists = MutableStateFlow<List<PlaylistSummary>>(emptyList())
    val importedPlaylists: StateFlow<List<PlaylistSummary>> = _importedPlaylists.asStateFlow()
    private val _playlistDetail = MutableStateFlow<PlaylistDetail?>(null)
    val playlistDetail: StateFlow<PlaylistDetail?> = _playlistDetail.asStateFlow()
    private val _playlistMessage = MutableStateFlow("")
    val playlistMessage: StateFlow<String> = _playlistMessage.asStateFlow()
    private val _ncmQrLogin = MutableStateFlow(NcmQrLoginState())
    val ncmQrLogin: StateFlow<NcmQrLoginState> = _ncmQrLogin.asStateFlow()
    private var qrLoginJob: Job? = null

    val settings = settingsRepository.settings.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        AppSettings(),
    )

    init { refreshDownloads() }

    fun scanLocalMusic() {
        viewModelScope.launch {
            _scanMessage.value = "正在扫描系统媒体库…"
            runCatching { localRepository.scanMediaStore() }
                .onSuccess { tracks ->
                    _localTracks.value = tracks
                    _scanMessage.value = if (tracks.isEmpty()) "未发现可播放的本地音乐" else "已发现 ${tracks.size} 首本地音乐"
                }
                .onFailure { error -> _scanMessage.value = "扫描失败：${error.message ?: "系统媒体库不可用"}" }
        }
    }

    fun hasMediaPermission(): Boolean = localRepository.canReadMedia()

    fun refreshDownloads() {
        viewModelScope.launch {
            _downloadMessage.value = "正在读取系统下载与 Music/轻音…"
            runCatching {
                downloadRepository.entries() to downloadRepository.downloadedTracks()
            }.onSuccess { (entries, tracks) ->
                _downloads.value = entries
                _downloadedTracks.value = tracks
                _downloadMessage.value = if (tracks.isEmpty()) "Music/轻音 暂无已完成的可播放文件" else "已发现 ${tracks.size} 首已下载音乐"
            }.onFailure { error ->
                _downloadMessage.value = "读取下载失败：${error.message ?: "请确认已授予音乐读取权限"}"
            }
        }
    }

    fun playDownloaded(track: Track) {
        val queue = _downloadedTracks.value
        playback.playQueue(queue, queue.indexOfFirst { it.id == track.id }.coerceAtLeast(0))
        loadLyrics(track)
    }

    fun scanCustomFolder(uri: Uri) {
        runCatching {
            getApplication<Application>().contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        viewModelScope.launch {
            _scanMessage.value = "正在扫描自定义音乐目录…"
            runCatching { customFolderRepository.scan(uri) }
                .onSuccess { customTracks ->
                    _localTracks.value = (_localTracks.value + customTracks).distinctBy { it.id }
                    _scanMessage.value = "已加载 ${customTracks.size} 首自定义目录音乐"
                    settingsRepository.update { it.copy(customFolderUri = uri.toString()) }
                }
                .onFailure { error -> _scanMessage.value = "自定义目录扫描失败：${error.message ?: "目录不可用"}" }
        }
    }

    fun restoreCustomFolder(uri: String) {
        if (uri.isNotBlank()) scanCustomFolder(Uri.parse(uri))
    }

    fun playLocal(track: Track) {
        val tracks = _localTracks.value
        playback.playQueue(tracks, tracks.indexOfFirst { it.id == track.id }.coerceAtLeast(0))
        loadLyrics(track)
    }

    fun loadDaily(force: Boolean = false) {
        viewModelScope.launch {
            _dailyMessage.value = if (force) "正在刷新每日推荐…" else ""
            runCatching { dailyRepository.get(settings.value, force) }
                .onSuccess { tracks ->
                    _dailyTracks.value = tracks
                    _dailyMessage.value = if (tracks.isEmpty()) "登录后可获取每日推荐" else "每日推荐"
                }
                .onFailure { error -> _dailyMessage.value = error.message ?: "每日推荐暂不可用" }
        }
    }

    fun loadMyPlaylists(force: Boolean = false, authenticatedSettings: AppSettings? = null) {
        val current = authenticatedSettings ?: settings.value
        if (current.ncmCookie.isBlank() || current.ncmUserId <= 0L) {
            _myPlaylists.value = emptyList()
            _playlistMessage.value = "登录网易云后可查看我的歌单"
            return
        }
        viewModelScope.launch {
            _playlistMessage.value = if (force) "正在刷新我的歌单…" else ""
            runCatching { playlistRepository.playlists(current, current.ncmUserId, force) }
                .onSuccess { list ->
                    _myPlaylists.value = list
                    _playlistMessage.value = if (list.isEmpty()) "没有可显示的歌单" else "共 ${list.size} 个歌单"
                }
                .onFailure { error -> _playlistMessage.value = error.message ?: "我的歌单加载失败" }
        }
    }

    fun loadImportedPlaylists() {
        viewModelScope.launch {
            _importedPlaylists.value = playlistRepository.cachedImported(settings.value.importedPlaylistIds)
        }
    }

    fun importPlaylist(source: Track.Source, input: String) {
        viewModelScope.launch {
            _playlistMessage.value = "正在导入${if (source == Track.Source.QQ) " QQ" else "网易云"}歌单…"
            runCatching { playlistRepository.import(settings.value, source, input.trim()) }
                .onSuccess { detail ->
                    val nextIds = (settings.value.importedPlaylistIds + detail.summary.id).distinct()
                    settingsRepository.update { it.copy(importedPlaylistIds = nextIds) }
                    _importedPlaylists.value = (_importedPlaylists.value.filterNot { it.id == detail.summary.id } + detail.summary)
                    _playlistMessage.value = "已导入：${detail.summary.name}"
                }
                .onFailure { error -> _playlistMessage.value = error.message ?: "歌单导入失败" }
        }
    }

    fun openPlaylist(playlist: PlaylistSummary, force: Boolean = false) {
        viewModelScope.launch {
            _playlistMessage.value = "正在加载 ${playlist.name}…"
            runCatching { playlistRepository.detail(settings.value, playlist, force) }
                .onSuccess { detail -> _playlistDetail.value = detail; _playlistMessage.value = "" }
                .onFailure { error -> _playlistMessage.value = error.message ?: "歌单详情加载失败" }
        }
    }

    fun closePlaylist() { _playlistDetail.value = null }

    fun searchOnline(source: Track.Source, keyword: String) {
        if (keyword.isBlank()) return
        viewModelScope.launch {
            _networkMessage.value = "正在搜索${if (source == Track.Source.QQ) " QQ 音乐" else "网易云音乐"}…"
            val result = when (source) {
                Track.Source.QQ -> runCatching { qqRepository.search(settings.value, keyword.trim()) }
                else -> runCatching { ncmRepository.search(settings.value, keyword.trim()) }
            }
            result.onSuccess { tracks ->
                _searchTracks.value = tracks
                _networkMessage.value = if (tracks.isEmpty()) "未找到相关歌曲" else "找到 ${tracks.size} 首${if (source == Track.Source.QQ) " QQ" else "网易云"}歌曲"
            }.onFailure { error ->
                _networkMessage.value = "搜索失败：${error.message ?: if (source == Track.Source.QQ) "QQ API 不可用或未配置 Key" else "NCMC 不可用"}"
            }
        }
    }

    fun playOnline(track: Track) {
        viewModelScope.launch {
            _networkMessage.value = "正在解析播放地址…"
            when (track.source) {
                Track.Source.QQ -> runCatching { qqRepository.resolve(settings.value, track) }
                    .onSuccess { resolved ->
                        playback.playQueue(listOf(resolved.track), 0)
                        _lyrics.value = LrcParser.parse(resolved.lyric)
                        _networkMessage.value = "正在播放：${resolved.track.title} · ${resolved.track.resolvedQqQuality?.label ?: "QQ"}"
                    }
                    .onFailure { error -> _networkMessage.value = "无法播放：${error.message ?: "QQ 没有可播放地址"}" }
                else -> runCatching { ncmRepository.resolvePlayback(settings.value, track) }
                    .onSuccess { playable ->
                        playback.playQueue(listOf(playable), 0)
                        _networkMessage.value = "正在播放：${playable.title} · ${playable.resolvedQuality?.label ?: "备用线路"}"
                        loadLyrics(playable)
                    }
                    .onFailure { error -> _networkMessage.value = "无法播放：${error.message ?: "没有可播放地址"}" }
            }
        }
    }

    fun playNcm(track: Track) = playOnline(track)

    private fun loadLyrics(track: Track) {
        _lyrics.value = emptyList()
        viewModelScope.launch {
            runCatching { lyricsRepository.load(settings.value, track) }
                .onSuccess { parsed -> _lyrics.value = parsed }
                .onFailure { _lyrics.value = emptyList() }
        }
    }

    fun startNcmQrLogin() {
        qrLoginJob?.cancel()
        viewModelScope.launch {
            _ncmQrLogin.value = NcmQrLoginState(NcmQrLoginState.Stage.LOADING, message = "正在生成二维码…")
            val initialSettings = settings.value
            runCatching {
                val key = ncmRepository.qrKey(initialSettings)
                ncmRepository.qrImage(initialSettings, key) to key
            }.onSuccess { (image, key) ->
                _ncmQrLogin.value = NcmQrLoginState(NcmQrLoginState.Stage.READY, image, "请使用网易云音乐 App 扫码")
                qrLoginJob = viewModelScope.launch {
                    while (isActive) {
                        delay(1_800)
                        runCatching { ncmRepository.qrCheck(initialSettings, key) }
                            .onSuccess { checked ->
                                when (checked.code) {
                                    801 -> _ncmQrLogin.value = NcmQrLoginState(NcmQrLoginState.Stage.READY, image, "等待扫码")
                                    802 -> _ncmQrLogin.value = NcmQrLoginState(NcmQrLoginState.Stage.SCANNED, image, "已扫码，请在网易云音乐 App 中确认")
                                    803 -> {
                                        val cookie = checked.cookie.replace(Regex(";\\s*(Max-Age|Expires)=[^;]+", RegexOption.IGNORE_CASE), "")
                                        if (cookie.isBlank()) {
                                            _ncmQrLogin.value = NcmQrLoginState(NcmQrLoginState.Stage.ERROR, image, "登录成功但服务未返回会话 Cookie")
                                        } else {
                                            val authenticated = initialSettings.copy(ncmCookie = cookie)
                                            val account = runCatching { ncmRepository.account(authenticated) }.getOrNull()
                                            val userId = account?.userId ?: 0L
                                            val signedIn = initialSettings.copy(ncmCookie = cookie, ncmNickname = account?.nickname.orEmpty(), ncmUserId = userId)
                                            settingsRepository.update { it.copy(ncmCookie = signedIn.ncmCookie, ncmNickname = signedIn.ncmNickname, ncmUserId = signedIn.ncmUserId) }
                                            if (userId > 0L) loadMyPlaylists(force = true, authenticatedSettings = signedIn)
                                            _ncmQrLogin.value = NcmQrLoginState(NcmQrLoginState.Stage.SUCCESS, image, "登录成功${account?.nickname?.let { " · $it" } ?: ""}")
                                        }
                                        qrLoginJob?.cancel()
                                    }
                                    800 -> { _ncmQrLogin.value = NcmQrLoginState(NcmQrLoginState.Stage.ERROR, image, "二维码已过期，请重新生成"); qrLoginJob?.cancel() }
                                }
                            }
                            .onFailure { error -> _ncmQrLogin.value = NcmQrLoginState(NcmQrLoginState.Stage.ERROR, image, "轮询失败：${error.message ?: "NCMC 不可用"}"); qrLoginJob?.cancel() }
                    }
                }
            }.onFailure { error -> _ncmQrLogin.value = NcmQrLoginState(NcmQrLoginState.Stage.ERROR, message = "二维码创建失败：${error.message ?: "NCMC 不可用"}") }
        }
    }

    fun cancelNcmQrLogin() {
        qrLoginJob?.cancel()
        qrLoginJob = null
        if (_ncmQrLogin.value.stage != NcmQrLoginState.Stage.SUCCESS) _ncmQrLogin.value = NcmQrLoginState()
    }

    fun logoutNcm() {
        cancelNcmQrLogin()
        _myPlaylists.value = emptyList()
        _playlistDetail.value = null
        updateSettings { it.copy(ncmCookie = "", ncmNickname = "", ncmUserId = 0L) }
    }

    fun enqueueDownload(track: Track) {
        viewModelScope.launch {
            _networkMessage.value = "正在按 ${settings.value.quality.label} 解析下载音源…"
            val resolved = runCatching {
                when (track.source) {
                    Track.Source.NETEASE -> ncmRepository.resolveDownload(settings.value, track)
                    Track.Source.QQ -> qqRepository.resolve(settings.value, track).track
                    else -> requireNotNull(track.remoteUrl) { "该曲目当前没有可下载的在线音源" }.let { track }
                }
            }
            resolved.onSuccess { downloadable ->
                val extension = downloadable.audioExtension
                    ?.lowercase()
                    ?.replace(Regex("[^a-z0-9]"), "")
                    ?.takeIf(String::isNotBlank)
                    ?: "mp3"
                val qualityLabel = downloadable.resolvedQuality?.label ?: downloadable.resolvedQqQuality?.label ?: settings.value.quality.label
                val fileName = "${downloadable.artist} - ${downloadable.title}.$extension"
                runCatching {
                    downloadRepository.enqueue(downloadable.remoteUrl!!, downloadable.title, "${downloadable.artist} · $qualityLabel", fileName)
                }.onSuccess {
                    _networkMessage.value = "已加入下载：${downloadable.title} · $qualityLabel"
                    refreshDownloads()
                }.onFailure { error ->
                    _networkMessage.value = "创建下载失败：${error.message ?: "系统下载服务不可用"}"
                }
            }.onFailure { error ->
                _networkMessage.value = error.message ?: "无法获取所选音质的下载地址"
            }
        }
    }

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { settingsRepository.update(transform) }
    }

    override fun onCleared() {
        qrLoginJob?.cancel()
        playback.release()
        super.onCleared()
    }
}
