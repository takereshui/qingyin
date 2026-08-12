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
    private val _downloadActionMessage = MutableStateFlow("")
    val downloadActionMessage: StateFlow<String> = _downloadActionMessage.asStateFlow()

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
    private val _localPlaylists = MutableStateFlow<List<PlaylistSummary>>(emptyList())
    val localPlaylists: StateFlow<List<PlaylistSummary>> = _localPlaylists.asStateFlow()
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

    init {
        refreshDownloads()
        loadLocalPlaylists()
    }

    fun scanLocalMusic() {
        viewModelScope.launch {
            _scanMessage.value = "正在扫描本地音乐目录…"
            val roots = settings.value.customFolderUris
            runCatching {
                val mediaTracks = if (localRepository.canReadMedia()) localRepository.scanMediaStore() else emptyList()
                val customTracks = roots.flatMap { uri -> customFolderRepository.scan(Uri.parse(uri)) }
                (mediaTracks + customTracks).distinctBy { it.id }
            }
                .onSuccess { tracks ->
                    _localTracks.value = tracks
                    _scanMessage.value = if (tracks.isEmpty()) "未发现可播放的本地音乐" else "已扫描 ${tracks.size} 首本地音乐 · ${roots.size} 个自定义目录"
                }
                .onFailure { error -> _scanMessage.value = "扫描失败：${error.message ?: "本地目录不可用"}" }
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

    fun scanCustomFolder(uri: Uri) = addCustomFolder(uri)

    fun addCustomFolder(uri: Uri) {
        runCatching {
            getApplication<Application>().contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        viewModelScope.launch {
            settingsRepository.update { current ->
                val next = (current.customFolderUris + uri.toString()).distinct()
                current.copy(customFolderUri = next.firstOrNull().orEmpty(), customFolderUris = next)
            }
            scanLocalMusic()
        }
    }

    fun removeCustomFolder(uri: String) {
        viewModelScope.launch {
            settingsRepository.update { current ->
                val next = current.customFolderUris.filterNot { it == uri }
                current.copy(customFolderUri = next.firstOrNull().orEmpty(), customFolderUris = next)
            }
            scanLocalMusic()
        }
    }

    fun restoreCustomFolders(uris: List<String>) {
        if (uris.isNotEmpty()) scanLocalMusic()
    }

    fun restoreCustomFolder(uri: String) {
        if (uri.isNotBlank()) restoreCustomFolders(listOf(uri))
    }

    fun playLocal(track: Track) {
        val tracks = _localTracks.value
        playback.playQueue(tracks, tracks.indexOfFirst { it.id == track.id }.coerceAtLeast(0))
        loadLyrics(track)
    }

    fun loadDaily(force: Boolean = false) {
        viewModelScope.launch {
            val cached = dailyRepository.cached()
            if (cached.isNotEmpty()) {
                _dailyTracks.value = cached
                _dailyMessage.value = "已加载本地每日推荐，正在同步…"
            } else {
                _dailyMessage.value = if (force) "正在刷新每日推荐…" else "正在同步每日推荐…"
            }
            // 每次打开均从线上同步；成功后 DailyRepository 会覆盖本地持久化缓存。
            runCatching { dailyRepository.get(settings.value, force = true) }
                .onSuccess { tracks ->
                    if (tracks.isNotEmpty()) _dailyTracks.value = tracks
                    _dailyMessage.value = when {
                        tracks.isNotEmpty() -> "每日推荐已同步到本地"
                        cached.isNotEmpty() -> "正在使用本地每日推荐"
                        else -> "登录后可获取每日推荐"
                    }
                }
                .onFailure { error ->
                    _dailyMessage.value = if (cached.isNotEmpty()) "正在使用本地每日推荐；同步失败" else error.message ?: "每日推荐暂不可用"
                }
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
            val cached = playlistRepository.cachedPlaylists(current.ncmUserId)
            if (cached.isNotEmpty()) {
                _myPlaylists.value = cached
                _playlistMessage.value = "已加载本地歌单，正在同步…"
            } else {
                _playlistMessage.value = if (force) "正在刷新我的歌单…" else "正在同步我的歌单…"
            }
            // 每次登录或打开时拉取线上目录；失败则继续保留本地列表。
            runCatching { playlistRepository.playlists(current, current.ncmUserId, force = true) }
                .onSuccess { list ->
                    if (list.isNotEmpty()) _myPlaylists.value = list
                    _playlistMessage.value = when {
                        list.isNotEmpty() -> "已同步 ${list.size} 个歌单到本地"
                        cached.isNotEmpty() -> "正在使用本地歌单"
                        else -> "没有可显示的歌单"
                    }
                }
                .onFailure { error ->
                    _playlistMessage.value = if (cached.isNotEmpty()) "正在使用本地歌单；同步失败" else error.message ?: "我的歌单加载失败"
                }
        }
    }

    fun loadLocalPlaylists() {
        viewModelScope.launch { _localPlaylists.value = playlistRepository.localPlaylists() }
    }

    fun createLocalPlaylist(name: String) {
        viewModelScope.launch {
            val created = playlistRepository.createLocalPlaylist(name)
            _localPlaylists.value = _localPlaylists.value + created
        }
    }

    fun deleteLocalPlaylist(playlist: PlaylistSummary) {
        if (playlist.source != Track.Source.LOCAL) return
        viewModelScope.launch {
            playlistRepository.deleteLocalPlaylist(playlist.id)
            _localPlaylists.value = _localPlaylists.value.filterNot { it.id == playlist.id }
            if (_playlistDetail.value?.summary?.id == playlist.id) _playlistDetail.value = null
        }
    }

    fun loadImportedPlaylists() {
        viewModelScope.launch {
            val cached = playlistRepository.cachedImported(settings.value.importedPlaylistIds)
            _importedPlaylists.value = cached
            // 已导入的网易云和 QQ 歌单均以本地详情先展示，再逐个刷新到本地。
            cached.forEach { summary ->
                runCatching { playlistRepository.detail(settings.value, summary, force = true) }
                    .onSuccess { detail ->
                        _importedPlaylists.value = _importedPlaylists.value
                            .filterNot { it.id == detail.summary.id } + detail.summary
                    }
            }
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
            val cached = playlistRepository.cachedDetail(playlist.id)
            if (cached != null) {
                _playlistDetail.value = cached
                _playlistMessage.value = "已加载本地歌单，正在同步…"
            } else {
                _playlistMessage.value = "正在加载 ${playlist.name}…"
            }
            runCatching { playlistRepository.detail(settings.value, playlist, force = true) }
                .onSuccess { detail ->
                    _playlistDetail.value = detail
                    _playlistMessage.value = ""
                }
                .onFailure { error ->
                    _playlistMessage.value = if (cached != null) "正在使用本地歌单；同步失败" else error.message ?: "歌单详情加载失败"
                }
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
        when (track.source) {
            Track.Source.QQ -> playQq(track)
            else -> playNetease(track, _networkMessage)
        }
    }

    /** 每日推荐使用独立提示，避免解析失败时错误信息只出现在搜索页而让用户误以为点击无响应。 */
    fun playDaily(track: Track) = playNetease(track, _dailyMessage)

    fun playNcm(track: Track) = playNetease(track, _networkMessage)

    private fun playQq(track: Track) {
        viewModelScope.launch {
            _networkMessage.value = "正在解析 QQ 播放地址…"
            runCatching { qqRepository.resolve(settings.value, track) }
                .onSuccess { resolved ->
                    playback.playQueue(listOf(resolved.track), 0)
                    _lyrics.value = LrcParser.parse(resolved.lyric)
                    if (resolved.lyric.isNotBlank()) lyricsRepository.save(resolved.track, resolved.lyric)
                    _networkMessage.value = "正在播放：${resolved.track.title} · ${resolved.track.resolvedQqQuality?.label ?: "QQ"}"
                }
                .onFailure { error -> _networkMessage.value = "QQ 无法播放：${error.message ?: "没有可播放地址"}" }
        }
    }

    private fun playNetease(track: Track, message: MutableStateFlow<String>) {
        viewModelScope.launch {
            message.value = "正在解析《${track.title}》的播放地址…"
            runCatching { ncmRepository.resolvePlayback(settings.value, track) }
                .onSuccess { playable ->
                    playback.playQueue(listOf(playable), 0)
                    message.value = "正在播放：${playable.title} · ${playable.resolvedQuality?.label ?: "备用线路"}"
                    loadLyrics(playable)
                }
                .onFailure { error ->
                    message.value = "无法播放《${track.title}》：${error.message ?: "没有可播放地址"}"
                }
        }
    }

    private fun loadLyrics(track: Track) {
        _lyrics.value = emptyList()
        viewModelScope.launch {
            val cached = runCatching { lyricsRepository.cached(track) }.getOrNull()
            if (cached != null) _lyrics.value = cached
            // 先显示本地歌词，再静默向线上同步；网络失败时继续使用本地结果。
            runCatching { lyricsRepository.refresh(settings.value, track) }
                .onSuccess { refreshed ->
                    if (refreshed.isNotEmpty() || cached == null) _lyrics.value = refreshed
                }
                .onFailure {
                    if (cached == null) _lyrics.value = emptyList()
                }
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
            val requestedQuality = if (track.source == Track.Source.QQ) settings.value.qqQuality.label else settings.value.quality.label
            _networkMessage.value = "正在按 $requestedQuality 解析下载音源…"
            _downloadMessage.value = "正在创建《${track.title}》下载任务…"
            _downloadActionMessage.value = "正在解析下载音源…"
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
                    _downloadMessage.value = "已加入下载：${downloadable.title} · $qualityLabel"
                    _downloadActionMessage.value = "已加入下载：${downloadable.title} · $qualityLabel"
                    refreshDownloads()
                }.onFailure { error ->
                    val text = "创建下载失败：${error.message ?: "系统下载服务不可用"}"
                    _networkMessage.value = text
                    _downloadMessage.value = text
                    _downloadActionMessage.value = text
                }
            }.onFailure { error ->
                val text = error.message ?: "无法获取所选音质的下载地址"
                _networkMessage.value = text
                _downloadMessage.value = text
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
