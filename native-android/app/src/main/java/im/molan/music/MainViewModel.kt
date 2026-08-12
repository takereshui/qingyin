package im.molan.music

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import im.molan.music.data.download.DownloadRepository
import im.molan.music.data.local.CustomFolderRepository
import im.molan.music.data.local.LocalMusicRepository
import im.molan.music.data.network.NcmRepository
import im.molan.music.data.settings.SettingsRepository
import im.molan.music.data.lyrics.LrcParser
import im.molan.music.model.AppSettings
import im.molan.music.model.Track
import im.molan.music.model.LyricLine
import im.molan.music.model.NcmQrLoginState
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
    private val settingsRepository = SettingsRepository(application)
    val playback = PlaybackConnection(application)

    private val _localTracks = MutableStateFlow<List<Track>>(emptyList())
    val localTracks: StateFlow<List<Track>> = _localTracks.asStateFlow()

    private val _scanMessage = MutableStateFlow("等待扫描本地音乐")
    val scanMessage: StateFlow<String> = _scanMessage.asStateFlow()

    private val _searchTracks = MutableStateFlow<List<Track>>(emptyList())
    val searchTracks: StateFlow<List<Track>> = _searchTracks.asStateFlow()
    private val _networkMessage = MutableStateFlow("可搜索网易云公开曲目")
    val networkMessage: StateFlow<String> = _networkMessage.asStateFlow()
    private val _lyrics = MutableStateFlow<List<LyricLine>>(emptyList())
    val lyrics: StateFlow<List<LyricLine>> = _lyrics.asStateFlow()
    private val _ncmQrLogin = MutableStateFlow(NcmQrLoginState())
    val ncmQrLogin: StateFlow<NcmQrLoginState> = _ncmQrLogin.asStateFlow()
    private var qrLoginJob: Job? = null

    val settings = settingsRepository.settings.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        AppSettings(),
    )

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
        _lyrics.value = emptyList()
        playback.playQueue(tracks, tracks.indexOfFirst { it.id == track.id }.coerceAtLeast(0))
    }

    fun searchNcm(keyword: String) {
        if (keyword.isBlank()) return
        viewModelScope.launch {
            _networkMessage.value = "正在搜索…"
            runCatching { ncmRepository.search(settings.value, keyword.trim()) }
                .onSuccess { tracks ->
                    _searchTracks.value = tracks
                    _networkMessage.value = if (tracks.isEmpty()) "未找到相关歌曲" else "找到 ${tracks.size} 首歌曲"
                }
                .onFailure { error -> _networkMessage.value = "搜索失败：${error.message ?: "NCMC 不可用"}" }
        }
    }

    fun playNcm(track: Track) {
        viewModelScope.launch {
            _networkMessage.value = "正在解析播放地址…"
            runCatching { ncmRepository.resolvePlayback(settings.value, track) }
                .onSuccess { playable ->
                    playback.playQueue(listOf(playable), 0)
                    _networkMessage.value = "正在播放：${playable.title}"
                    loadNcmLyric(playable)
                }
                .onFailure { error -> _networkMessage.value = "无法播放：${error.message ?: "没有可播放地址"}" }
        }
    }

    private fun loadNcmLyric(track: Track) {
        viewModelScope.launch {
            runCatching { ncmRepository.lyric(settings.value, track) }
                .onSuccess { (lrc, translated) -> _lyrics.value = LrcParser.parse(lrc, translated) }
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
                                            settingsRepository.update { it.copy(ncmCookie = cookie, ncmNickname = account?.nickname.orEmpty()) }
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
        updateSettings { it.copy(ncmCookie = "", ncmNickname = "") }
    }

    fun enqueueDownload(track: Track) {
        val url = track.remoteUrl ?: return
        val fileName = "${track.artist} - ${track.title}.mp3"
        runCatching { downloadRepository.enqueue(url, track.title, track.artist, fileName) }
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
