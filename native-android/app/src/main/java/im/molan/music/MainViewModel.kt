package im.molan.music

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
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
import im.molan.music.data.match.TrackMatcher
import im.molan.music.model.AppSettings
import im.molan.music.model.DownloadEntry
import im.molan.music.model.Track
import im.molan.music.model.LyricLine
import im.molan.music.model.NcmQrLoginState
import im.molan.music.model.PlaylistDetail
import im.molan.music.model.PlayerSnapshot
import im.molan.music.model.PlaylistSummary
import im.molan.music.playback.PlaybackConnection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as QingyinApplication
    private val localRepository = LocalMusicRepository(application)
    private val customFolderRepository = CustomFolderRepository(application)
    private val downloadRepository = app.downloadRepository
    private val ncmRepository = NcmRepository()
    private val dailyRepository = DailyRepository(application, ncmRepository)
    private val playlistRepository = PlaylistRepository(application, ncmRepository)
    private val qqRepository = QqRepository()
    private val lyricsRepository = LyricsRepository(ncmRepository, app.database.lyricDao())
    private val settingsRepository = SettingsRepository(application)
    val playback = PlaybackConnection(application)

    private val _localTracks = MutableStateFlow<List<Track>>(emptyList())
    val localTracks: StateFlow<List<Track>> = _localTracks.asStateFlow()
    /** 统一歌曲匹配的内存索引：下载音源优先于普通本地扫描结果。 */
    private var localMatchIndex: Map<String, List<Track>> = emptyMap()
    /** 本地音源索引每次刷新后清空；同一线上歌曲在列表重组期间只评分一次。 */
    private val localMatchCache = mutableMapOf<String, TrackMatcher.Result>()
    private val localNoMatchCache = mutableSetOf<String>()

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
    /** 下载列表结构指纹：只有任务增减/状态变化才重扫已下载歌曲。 */
    private var lastDownloadsKey: List<Triple<Long, DownloadEntry.Status, String>> = emptyList()

    private val _searchTracks = MutableStateFlow<List<Track>>(emptyList())
    val searchTracks: StateFlow<List<Track>> = _searchTracks.asStateFlow()
    /** 组合期只查内存标志位，绝不在 Row 重组期间执行 Levenshtein 全量评分。 */
    private val _localMatchFlags = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val localMatchFlags: StateFlow<Map<String, Boolean>> = _localMatchFlags.asStateFlow()
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
    /** 每次切歌都会更换会话键并取消旧请求，杜绝上一首歌词异步回写到当前界面。 */
    private var lyricsJob: Job? = null
    private var activeLyricKey: String = ""
    private var queueResolveJob: Job? = null
    private var resolvingQueueTrackId: String? = null
    private var backgroundParseJob: Job? = null
    /** 同一 trackId 仅自动重解析一次，防 IO_NETWORK 死循环 */
    private var lastAutoReresolveId: String? = null

    val settings = settingsRepository.settings.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        AppSettings(),
    )

    init {
        observeInternalDownloads()
        observeCurrentQueueTrack()
        observePlaybackNetworkErrors()
        loadLocalPlaylists()
        loadLocalMusicIndex()
    }

    private fun observeInternalDownloads() {
        viewModelScope.launch {
            downloadRepository.taskEntries.collect { entries ->
                _downloads.value = entries
                // 只有完成/重试等结构变化才重扫下载歌曲；纯字节进度刷新只更新任务列表，
                // 避免下载过程中每 512KB 都全量扫描文件并重建匹配索引造成卡顿。
                val structureKey = entries.map { Triple(it.id, it.status, it.fileName) }
                if (structureKey != lastDownloadsKey) {
                    lastDownloadsKey = structureKey
                    val tracks = downloadRepository.downloadedTracks()
                    _downloadedTracks.value = tracks
                    rebuildLocalMatchIndex()
                    refreshShownMatchFlags()
                    // 新下载已发布进系统媒体库：节流调度本地重扫，新歌尽快出现在本地页且不刷屏。
                    if (tracks.isNotEmpty() && localRepository.canReadMedia()) scheduleLocalScan()
                }
                _downloadMessage.value = when {
                    entries.any { it.status == DownloadEntry.Status.DOWNLOADING } -> "轻音内置下载器正在下载 ${entries.count { it.status == DownloadEntry.Status.DOWNLOADING }} 个任务"
                    _downloadedTracks.value.isNotEmpty() -> "已完成 ${_downloadedTracks.value.size} 首下载音乐"
                    entries.any { it.status == DownloadEntry.Status.QUEUED } -> "下载任务等待中"
                    else -> "暂无下载任务"
                }
            }
        }
    }

    /**
     * 冷启动先读上次扫描的本地索引立即展示，避免等待全量扫描或扫描前页面为空。
     * 本地索引落盘后与持久化的本地歌单在同一启动阶段就绪，线上曲目的“本地版”匹配标志可直接生效。
     */
    private fun loadLocalMusicIndex() {
        viewModelScope.launch {
            if (_localTracks.value.isNotEmpty()) return@launch
            val cached = runCatching { localRepository.loadIndex() }.getOrDefault(emptyList())
            if (cached.isEmpty()) return@launch
            _localTracks.value = cached
            rebuildLocalMatchIndex()
            refreshShownMatchFlags()
            _scanMessage.value = "已载入上次扫描的 ${cached.size} 首本地音乐，正在后台刷新…"
        }
    }

    /** 扫描互斥：并发请求合并为一次，避免下载/权限回调/手动按钮同时触发全量扫描。 */
    private val scanGate = Mutex()
    private var scanQueued = false

    /**
     * 全量扫描本地音乐（MediaStore + 自定义目录）并落盘索引。
     * 单飞：扫描进行中的新请求只置位排队，结束后自动补一次，绝不并发重建索引。
     */
    fun scanLocalMusic() {
        viewModelScope.launch {
            if (!scanGate.tryLock()) {
                scanQueued = true
                return@launch
            }
            try {
                _scanMessage.value = "正在扫描本地音乐目录…"
                val roots = settings.value.customFolderUris
                runCatching {
                    val mediaTracks = if (localRepository.canReadMedia()) localRepository.scanMediaStore() else emptyList()
                    val customTracks = roots.flatMap { uri -> customFolderRepository.scan(Uri.parse(uri)) }
                    (mediaTracks + customTracks).distinctBy { it.id }
                }
                    .onSuccess { tracks ->
                        _localTracks.value = tracks
                        localRepository.saveIndex(tracks)
                        rebuildLocalMatchIndex()
                        refreshShownMatchFlags()
                        _scanMessage.value = if (tracks.isEmpty()) "未发现可播放的本地音乐" else "已扫描 ${tracks.size} 首本地音乐 · ${roots.size} 个自定义目录"
                    }
                    .onFailure { error -> _scanMessage.value = "扫描失败：${error.message ?: "本地目录不可用"}" }
            } catch (_: Throwable) {
                _scanMessage.value = "扫描本地音乐时出现异常，请稍后重试"
            } finally {
                val rerun = scanQueued
                scanQueued = false
                scanGate.unlock()
                if (rerun) scanLocalMusic()
            }
        }
    }

    /** 自动触发的本地重扫（下载完成/结构变化）：10 秒内最多一次，避免批量下载期间全量扫描刷屏。 */
    private var lastAutoScanAt = 0L
    private fun scheduleLocalScan() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastAutoScanAt < 10_000L) return
        lastAutoScanAt = now
        scanLocalMusic()
    }

    fun hasMediaPermission(): Boolean = localRepository.canReadMedia()

    fun refreshDownloads() {
        viewModelScope.launch {
            _downloadMessage.value = "正在读取轻音内置下载队列…"
            runCatching {
                downloadRepository.entries() to downloadRepository.downloadedTracks()
            }.onSuccess { (entries, tracks) ->
                _downloads.value = entries
                _downloadedTracks.value = tracks
                rebuildLocalMatchIndex()
                refreshShownMatchFlags()
                _downloadMessage.value = if (tracks.isEmpty()) "轻音下载目录暂无已完成的可播放文件" else "已发现 ${tracks.size} 首已下载音乐"
            }.onFailure { error ->
                _downloadMessage.value = "读取下载队列失败：${error.message ?: "下载目录不可用"}"
            }
        }
    }

    fun retryDownload(id: Long) = downloadRepository.retry(id)

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

    /** 设置 SAF 下载目录；空 treeUri 视为恢复默认公共 Music/轻音下载。 */
    fun setDownloadFolder(uri: Uri?) {
        if (uri != null) {
            runCatching {
                getApplication<Application>().contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
        }
        updateSettings { it.copy(downloadFolderUri = uri?.toString().orEmpty()) }
    }

    fun clearDownloadFolder() = updateSettings { it.copy(downloadFolderUri = "") }

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
                    if (tracks.isNotEmpty()) {
                        _dailyTracks.value = tracks
                        precomputeMatchFlags(tracks)
                    }
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
                    persistOnlinePlaylistMetadata(detail)
                    _playlistMessage.value = "已导入并保存到本地：${detail.summary.name}"
                }
                .onFailure { error -> _playlistMessage.value = error.message ?: "歌单导入失败" }
        }
    }

    fun openPlaylist(playlist: PlaylistSummary, force: Boolean = false) {
        viewModelScope.launch {
            val cached = playlistRepository.cachedDetail(playlist.id)
            if (cached != null) {
                _playlistDetail.value = cached
                precomputeMatchFlags(cached.tracks)
                _playlistMessage.value = "已加载本地歌单，正在同步…"
            } else {
                _playlistMessage.value = "正在加载 ${playlist.name}…"
            }
            runCatching { playlistRepository.detail(settings.value, playlist, force = true) }
                .onSuccess { detail ->
                    _playlistDetail.value = detail
                    precomputeMatchFlags(detail.tracks)
                    persistOnlinePlaylistMetadata(detail)
                    _playlistMessage.value = if (detail.summary.source == Track.Source.LOCAL) "" else "已保存 ${detail.tracks.size} 首曲目到本地歌单缓存；音频不会自动下载"
                }
                .onFailure { error ->
                    _playlistMessage.value = if (cached != null) "正在使用本地歌单；同步失败" else error.message ?: "歌单详情加载失败"
                }
        }
    }

    fun closePlaylist() { _playlistDetail.value = null }

    /** 线上歌单曲目加载成功后自动落为本地元数据副本，并开启后台静默全量解析任务。 */
    private suspend fun persistOnlinePlaylistMetadata(detail: PlaylistDetail) {
        if (detail.summary.source == Track.Source.LOCAL) return
        val localDetail = playlistRepository.syncAsLocalPlaylist(detail)
        _localPlaylists.value = _localPlaylists.value
            .filterNot { it.id == localDetail.summary.id } + localDetail.summary
        // 开启后台全量解析，将 API 链路直接写入本地数据库（JSON 缓存）。
        startBackgroundPlaylistParsing(localDetail)
    }

    private fun startBackgroundPlaylistParsing(detail: PlaylistDetail) {
        backgroundParseJob?.cancel()
        backgroundParseJob = viewModelScope.launch {
            val targets = detail.tracks.filter { !playback.isTrackPlayable(it) && (it.source == Track.Source.NETEASE || it.source == Track.Source.QQ) }
            if (targets.isEmpty()) return@launch
            val resolvedTracks = detail.tracks.toMutableList()
            targets.chunked(2).forEach { batch ->
                coroutineScope {
                    batch.forEach { track ->
                        launch {
                            runCatching { withTimeout(20_000L) { resolveDownloadTrack(track) } }.onSuccess { playable ->
                                val idx = resolvedTracks.indexOfFirst { it.id == track.id }
                                if (idx >= 0) {
                                    resolvedTracks[idx] = playable
                                    // 实时写回数据库，让“解析”工作在后台无感完成。
                                    playlistRepository.syncAsLocalPlaylist(detail.copy(tracks = resolvedTracks))
                                }
                            }
                        }
                    }
                }
                delay(1000) // 细水长流，避免对 API 造成突发压力。
            }
        }
    }

    fun searchOnline(source: Track.Source, keyword: String) {
        if (keyword.isBlank()) return
        viewModelScope.launch {
            _networkMessage.value = "正在搜索${if (source == Track.Source.QQ) " QQ 音乐" else "网易云音乐"}…"
            val result = when (source) {
                Track.Source.QQ -> runCatching { qqRepository.search(settings.value, keyword.trim()) }
                else -> runCatching { ncmRepository.search(settings.value, keyword.trim()) }
            }
            result.onSuccess { tracks ->
                val (ordered, localReady) = withContext(Dispatchers.Default) {
                    val matches = tracks.associateWith(::findLocalMatch)
                    tracks.sortedByDescending { matches[it]?.score ?: -1f } to matches.count { it.value != null }
                }
                _searchTracks.value = ordered
                precomputeMatchFlags(ordered)
                _networkMessage.value = when {
                    tracks.isEmpty() -> "未找到相关歌曲"
                    localReady > 0 -> "找到 ${tracks.size} 首歌曲，其中 ${localReady} 首可直接本地播放"
                    else -> "找到 ${tracks.size} 首${if (source == Track.Source.QQ) " QQ" else "网易云"}歌曲"
                }
            }.onFailure { error ->
                _networkMessage.value = "搜索失败：${error.message ?: if (source == Track.Source.QQ) "QQ API 不可用或未配置 Key" else "NCMC 不可用"}"
            }
        }
    }

    fun playOnline(track: Track) = playWithLocalPriority(track, _networkMessage)

    /** 每日推荐使用独立提示，避免解析失败时错误信息只出现在搜索页而让用户误以为点击无响应。 */
    fun playDaily(track: Track) = playWithLocalPriority(track, _dailyMessage)

    fun playNcm(track: Track) = playWithLocalPriority(track, _networkMessage)

    /**
     * 点击歌单曲目时立即以原始歌单顺序建立队列；不再等待整张歌单逐首解析。
     * 每个线上曲目只会在播放器真正切换到它时，才请求其所属来源的 API 获取播放地址。
     */
    fun playPlaylist(tracks: List<Track>, selected: Track) {
        if (tracks.isEmpty()) return
        val startIndex = tracks.indexOfFirst { it.id == selected.id }
        if (startIndex < 0) return
        beginLyricSession(selected)
        playback.playQueue(tracks, startIndex)
        _playlistMessage.value = "已载入 ${tracks.size} 首 · 正在播放《${selected.title}》"
    }

    /** 监听播放器当前项；只有真正切到尚无地址或地址已过期的线上曲目时才进行来源 API 解析。 */
    private fun observeCurrentQueueTrack() {
        viewModelScope.launch {
            playback.snapshot.collect { snapshot ->
                val current = snapshot.current ?: return@collect
                val needsResolve = !playback.isTrackPlayable(current) && (current.source == Track.Source.NETEASE || current.source == Track.Source.QQ)
                if (needsResolve) resolveCurrentQueueTrack(current) else {
                    // 成功就绪后允许下次网络失败再自动重试该 id
                    if (lastAutoReresolveId == current.id) lastAutoReresolveId = null
                    ensureLyricsForCurrent(current)
                }
                // 预解析：如果当前曲目已就绪，静默解析下一首，确保护航秒开。
                if (!needsResolve) lookAheadResolve(snapshot)
            }
        }
    }

    /** CDN URL 过期导致 IO_NETWORK 时，强制清掉 resolved 并重拉一次。 */
    private fun observePlaybackNetworkErrors() {
        viewModelScope.launch {
            playback.errorMessage.collect { msg ->
                if (msg.isBlank()) return@collect
                val current = playback.snapshot.value.current ?: return@collect
                if (current.source != Track.Source.NETEASE && current.source != Track.Source.QQ) return@collect
                if (!msg.contains("IO_NETWORK") && !msg.contains("网络")) return@collect
                if (lastAutoReresolveId == current.id) return@collect
                lastAutoReresolveId = current.id
                resolveCurrentQueueTrack(current.copy(remoteUrl = null, resolvedAt = 0L))
            }
        }
    }

    private fun lookAheadResolve(snapshot: PlayerSnapshot) {
        val nextIdx = snapshot.currentIndex + 1
        if (nextIdx !in snapshot.queue.indices) return
        val nextTrack = snapshot.queue[nextIdx]
        if (playback.isTrackPlayable(nextTrack) || (nextTrack.source != Track.Source.NETEASE && nextTrack.source != Track.Source.QQ)) return
        viewModelScope.launch {
            runCatching { withTimeout(15_000L) { resolveDownloadTrack(nextTrack) } }.onSuccess { playable ->
                playback.updateQueueItem(nextIdx, playable)
                // 同时静默写回本地缓存，让持久化链路随听随刷。
                val detail = _playlistDetail.value
                if (detail != null && detail.tracks.any { it.id == nextTrack.id }) {
                    val nextTracks = detail.tracks.toMutableList()
                    val idx = nextTracks.indexOfFirst { it.id == nextTrack.id }
                    if (idx >= 0) {
                        nextTracks[idx] = playable
                        playlistRepository.syncAsLocalPlaylist(detail.copy(tracks = nextTracks))
                    }
                }
            }
        }
    }

    private fun resolveCurrentQueueTrack(track: Track) {
        if (resolvingQueueTrackId == track.id && queueResolveJob?.isActive == true) return
        queueResolveJob?.cancel()
        resolvingQueueTrackId = track.id
        queueResolveJob = viewModelScope.launch {
            _playlistMessage.value = "正在请求《${track.title}》的${if (track.source == Track.Source.QQ) " QQ" else "网易云"}播放地址…"
            runCatching {
                withTimeout(25_000L) {
                    findLocalMatch(track)?.let { return@withTimeout it.local to it }
                    val resolved = when (track.source) {
                        Track.Source.NETEASE -> ncmRepository.resolvePlayback(settings.value, track)
                        Track.Source.QQ -> qqRepository.resolve(settings.value, track).let { response ->
                            if (response.lyric.isNotBlank()) lyricsRepository.save(response.track, response.lyric)
                            response.track
                        }
                        else -> track
                    }
                    resolved to null
                }
            }.onSuccess { (playable, localMatch) ->
                // 用户若已切歌或重建队列，旧解析结果绝不能替换新的当前项。
                if (playback.snapshot.value.current?.id != track.id) return@onSuccess
                playback.replaceCurrentAndPlay(playable)
                if (localMatch != null) loadMatchedLyrics(playable, track) else loadLyrics(playable)
                _playlistMessage.value = "正在播放《${track.title}》"
            }.onFailure { error ->
                if (playback.snapshot.value.current?.id == track.id) {
                    _playlistMessage.value = "无法播放《${track.title}》：${error.message ?: "播放地址获取失败"}"
                }
            }
            resolvingQueueTrackId = null
        }
    }

    /**
     * 统一播放入口。远程身份保留给歌词、封面和歌单；只在音频载体层使用可信的本地替代。
     * 匹配失败时才走 QQ / 网易云解析，因此不会因模糊同名歌曲而误播。
     */
    private fun playWithLocalPriority(track: Track, message: MutableStateFlow<String>) {
        when (track.source) {
            Track.Source.LOCAL -> { playLocal(track); return }
            Track.Source.DOWNLOADED -> { playDownloaded(track); return }
            else -> Unit
        }
        val matched = findLocalMatch(track)
        if (matched != null) {
            val queue = localPlaybackCandidates()
            playback.playQueue(queue, queue.indexOfFirst { it.id == matched.local.id }.coerceAtLeast(0))
            message.value = "本地优先播放：${matched.local.title} · 匹配度 ${matched.score.toInt()}%"
            // 本地缓存优先；若本地没有歌词，再以线上身份刷新歌词和翻译。
            loadMatchedLyrics(matched.local, track)
            return
        }
        when (track.source) {
            Track.Source.QQ -> playQq(track)
            else -> playNetease(track, message)
        }
    }

    private fun localPlaybackCandidates(): List<Track> =
        (_downloadedTracks.value + _localTracks.value).distinctBy { it.id }

    private fun rebuildLocalMatchIndex() {
        synchronized(localMatchCache) {
            localMatchIndex = localPlaybackCandidates()
                .flatMap { candidate -> TrackMatcher.indexKeys(candidate).map { key -> key to candidate } }
                .groupBy({ it.first }, { it.second })
            localMatchCache.clear()
            localNoMatchCache.clear()
        }
        _localMatchFlags.value = emptyMap()
    }

    /**
     * 批量预计算线上曲目的“本地匹配”标志位。列表组合期间只查这张表，
     * 绝不在 Row 重组的热路径里执行 TrackMatcher 全量 Levenshtein 评分。
     * 已算过的 ID 不再重复计算；索引重建后整表清空，由下一次预计算补齐。
     */
    fun precomputeMatchFlags(tracks: List<Track>) {
        val targets = tracks.filter {
            it.source == Track.Source.NETEASE || it.source == Track.Source.QQ
        }.filter { it.id !in _localMatchFlags.value }
        if (targets.isEmpty()) return
        viewModelScope.launch(Dispatchers.Default) {
            val additions = buildMap {
                targets.forEach { track -> put(track.id, findLocalMatch(track) != null) }
            }
            _localMatchFlags.value = _localMatchFlags.value + additions
        }
    }

    /** 本地音源变化后，对当前界面展示的线上列表统一补齐匹配标志。 */
    private fun refreshShownMatchFlags() {
        precomputeMatchFlags(_searchTracks.value)
        precomputeMatchFlags(_dailyTracks.value)
        _playlistDetail.value?.tracks?.let(::precomputeMatchFlags)
    }

    private fun findLocalMatch(remote: Track): TrackMatcher.Result? = synchronized(localMatchCache) {
        val cacheKey = "${remote.source.name}:${remote.id}"
        localMatchCache[cacheKey]?.let { return@synchronized it }
        if (cacheKey in localNoMatchCache) return@synchronized null
        val indexed = TrackMatcher.indexKeys(remote)
            .flatMap { key -> localMatchIndex[key].orEmpty() }
            .distinctBy { it.id }
        // 索引没有共同的规范标题键或艺人标题键即视为不匹配；不再全量评分，
        // 避免名称无关的歌曲因短字符串、时长或偶然元数据而被错误标为“本地”。
        if (indexed.isEmpty()) {
            localNoMatchCache += cacheKey
            return@synchronized null
        }
        val result = TrackMatcher.findBest(remote, indexed)
        if (result == null) localNoMatchCache += cacheKey else localMatchCache[cacheKey] = result
        result
    }

    /** 仅供播放决策等点击路径使用；列表展示一律查询 localMatchFlags。 */
    fun hasLocalMatch(track: Track): Boolean =
        track.source != Track.Source.LOCAL && track.source != Track.Source.DOWNLOADED && findLocalMatch(track) != null

    private fun playQq(track: Track) {
        beginLyricSession(track)
        viewModelScope.launch {
            _networkMessage.value = "正在解析 QQ 播放地址…"
            runCatching { qqRepository.resolve(settings.value, track) }
                .onSuccess { resolved ->
                    playback.playQueue(listOf(resolved.track), 0)
                    if (resolved.lyric.isNotBlank()) lyricsRepository.save(resolved.track, resolved.lyric)
                    loadLyrics(resolved.track)
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

    /** 在任何播放入口切歌前立即清空上一首歌词，并创建新的受控歌词会话。 */
    private fun beginLyricSession(track: Track): String {
        lyricsJob?.cancel()
        val key = "${track.source.name}:${track.id}:${System.nanoTime()}"
        activeLyricKey = key
        _lyrics.value = emptyList()
        return key
    }

    /** 供全屏播放器在切歌后重新确保当前曲目的歌词会话已建立。 */
    fun ensureLyricsForCurrent(track: Track) {
        if (!activeLyricKey.startsWith("${track.source.name}:${track.id}:")) loadLyrics(track)
    }

    private fun loadLyrics(track: Track) {
        val key = beginLyricSession(track)
        lyricsJob = viewModelScope.launch {
            val cached = runCatching { lyricsRepository.cached(track) }.getOrNull()
            if (activeLyricKey == key && cached != null) _lyrics.value = cached
            // QQ 歌词由 QQ 解析结果直接缓存；不再错误地经网易云搜索刷新。
            if (track.source == Track.Source.QQ) return@launch
            // 先显示本地歌词，再静默向线上同步；网络失败时继续使用本地结果。
            runCatching { lyricsRepository.refresh(settings.value, track) }
                .onSuccess { refreshed ->
                    if (activeLyricKey == key && (refreshed.isNotEmpty() || cached == null)) _lyrics.value = refreshed
                }
                .onFailure {
                    if (activeLyricKey == key && cached == null) _lyrics.value = emptyList()
                }
        }
    }

    /** 本地音源命中线上曲目时，先读本地文件/下载记录关联的歌词缓存，再回退线上刷新。 */
    private fun loadMatchedLyrics(local: Track, remote: Track) {
        // 会话按实际播放的本地音源绑定；远程身份仅用于读取/刷新对应歌词。
        val key = beginLyricSession(local)
        lyricsJob = viewModelScope.launch {
            val localCached = runCatching { lyricsRepository.cached(local) }.getOrNull()
            val remoteCached = runCatching { lyricsRepository.cached(remote) }.getOrNull()
            val initial = localCached?.takeIf { it.isNotEmpty() } ?: remoteCached
            if (activeLyricKey == key && initial != null) _lyrics.value = initial
            // QQ 的歌词使用其原始缓存，不能混入网易云歌词链路。
            if (remote.source == Track.Source.QQ) return@launch
            runCatching { lyricsRepository.refresh(settings.value, remote) }
                .onSuccess { refreshed ->
                    if (activeLyricKey == key && (refreshed.isNotEmpty() || initial == null)) _lyrics.value = refreshed
                }
                .onFailure {
                    if (activeLyricKey == key && initial == null) _lyrics.value = emptyList()
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

    /**
     * 同步仅拉取歌单与曲目元数据并持久化到应用本地缓存，绝不下载音频文件。
     * 单曲下载仍仅由播放器中的“下载”按钮触发，避免同步时产生意外流量和存储占用。
     */
    fun syncPlaylistToLocal(playlist: PlaylistSummary) {
        if (playlist.source == Track.Source.LOCAL) return
        viewModelScope.launch {
            _playlistMessage.value = "正在同步《${playlist.name}》到本地…"
            runCatching { playlistRepository.detail(settings.value, playlist, force = true) }
                .onSuccess { detail ->
                    val localId = "local:sync:${detail.summary.source.name.lowercase()}:${detail.summary.id}"
                    val existed = _localPlaylists.value.any { it.id == localId }
                    val localDetail = playlistRepository.syncAsLocalPlaylist(detail)
                    _playlistDetail.value = localDetail
                    _localPlaylists.value = _localPlaylists.value.filterNot { it.id == localDetail.summary.id } + localDetail.summary
                    _playlistMessage.value = if (existed) {
                        "已覆盖更新本地歌单《${localDetail.summary.name}》的 ${localDetail.tracks.size} 首曲目；音频不会自动下载"
                    } else {
                        "已新建本地歌单《${localDetail.summary.name}》，共 ${localDetail.tracks.size} 首曲目；音频不会自动下载"
                    }
                }
                .onFailure { error ->
                    _playlistMessage.value = "歌单同步失败：${error.message ?: "无法读取歌单曲目"}"
                }
        }
    }

    /** 将歌单中尚未严格匹配到本地音源的在线曲目批量解析并加入内置下载队列。 */
    fun enqueueMissingPlaylistTracks(tracks: List<Track>) {
        val targets = tracks.filter { track ->
            track.source != Track.Source.LOCAL && track.source != Track.Source.DOWNLOADED && !hasLocalMatch(track)
        }
        if (targets.isEmpty()) {
            _playlistMessage.value = "该歌单的曲目均已在本地可用，无需下载"
            return
        }
        viewModelScope.launch {
            _playlistMessage.value = "正在解析 ${targets.size} 首未本地匹配歌曲的下载音源…"
            // 大歌单按 3 首一批解析，控制内存与接口压力；内置下载器仍自行维持 3 路下载。
            val outcomes = mutableListOf<Result<DownloadRepository.EnqueueResult>>()
            targets.chunked(3).forEach { batch ->
                outcomes += coroutineScope {
                    batch.map { track ->
                        async {
                            runCatching {
                                withTimeout(25_000L) { resolveDownloadTrack(track) }
                            }.mapCatching(::enqueueResolvedDownload)
                        }
                    }.awaitAll()
                }
            }
            val added = outcomes.count { it.getOrNull()?.added == true }
            val existing = outcomes.count { it.getOrNull()?.added == false }
            val failed = outcomes.count { it.isFailure }
            refreshDownloads()
            _playlistMessage.value = buildString {
                append("批量下载已处理 ${targets.size} 首：新增 $added 首")
                if (existing > 0) append("，$existing 首已在下载队列或本地下载记录中")
                if (failed > 0) append("，$failed 首解析失败")
            }
            _downloadActionMessage.value = if (failed == 0) "歌单下载任务已加入内置队列" else "部分歌单曲目未能解析，请在下载页查看任务"
        }
    }

    fun enqueueDownload(track: Track) {
        viewModelScope.launch {
            val isNcm = track.source == Track.Source.NETEASE
            val requestedLabel = if (isNcm) settings.value.quality.label else settings.value.qqQuality.label
            val requestedWire = if (isNcm) settings.value.quality.wireValue else settings.value.qqQuality.wireValue
            _networkMessage.value = "正在按 $requestedLabel 解析下载音源…"
            _downloadMessage.value = "正在创建《${track.title}》下载任务…"
            _downloadActionMessage.value = "正在解析下载音源…"
            runCatching {
                withTimeout(25_000L) { resolveDownloadTrack(track) }
            }.onSuccess { resolved ->
                val onlineCache = app.onlineCache
                val url = resolved.remoteUrl
                val cacheHit = url != null && onlineCache.isFullyCached(url)
                val enqueueResult = if (cacheHit) {
                    runCatching { enqueueResolvedFromCache(resolved, url!!) }
                } else {
                    runCatching { enqueueResolvedDownload(resolved) }
                }
                val text = if (enqueueResult.isSuccess) {
                    val enqueue = enqueueResult.getOrThrow()
                    val actualLabel = resolved.resolvedQuality?.label ?: resolved.resolvedQqQuality?.label
                    val actualWire = resolved.resolvedQuality?.wireValue ?: resolved.resolvedQqQuality?.wireValue
                    buildString {
                        append(if (enqueue.added) "已加入下载：${track.title} · ${actualLabel ?: requestedLabel}" else "下载任务已存在：${track.title}")
                        if (cacheHit) append("（试听缓存已命中，直接另存，无需重新下载）")
                        if (actualWire != null && actualWire != requestedWire) append("（所选音质不可用，已按 $actualLabel 降级）")
                    }
                } else {
                    "下载任务创建失败：${enqueueResult.exceptionOrNull()?.message ?: "未知错误"}"
                }
                _networkMessage.value = text
                _downloadMessage.value = text
                _downloadActionMessage.value = text
                refreshDownloads()
            }.onFailure { error ->
                val text = "下载解析失败：${error.message ?: "无法获取所选音质的下载地址"}"
                _networkMessage.value = text
                _downloadMessage.value = text
                _downloadActionMessage.value = text
            }
        }
    }

    private suspend fun resolveDownloadTrack(track: Track): Track = when (track.source) {
        Track.Source.NETEASE -> ncmRepository.resolveDownload(settings.value, track)
        Track.Source.QQ -> qqRepository.resolveDownload(settings.value, track)
        else -> requireNotNull(track.remoteUrl) { "该曲目当前没有可下载的在线音源" }.let { track }
    }

    private fun enqueueResolvedDownload(downloadable: Track): DownloadRepository.EnqueueResult {
        val (fileName, qualityLabel, referer) = downloadFileSpec(downloadable)
        return downloadRepository.enqueueIfAbsent(
            requireNotNull(downloadable.remoteUrl) { "解析结果未包含下载地址" },
            downloadable.title,
            "${downloadable.artist} · $qualityLabel",
            fileName,
            referer,
        )
    }

    /** 试听缓存已完整命中：直接从缓存字节写入下载目录，不经网络。 */
    private fun enqueueResolvedFromCache(downloadable: Track, url: String): DownloadRepository.EnqueueResult {
        val (fileName, qualityLabel, referer) = downloadFileSpec(downloadable)
        return downloadRepository.enqueueFromCached(
            url,
            downloadable.title,
            "${downloadable.artist} · $qualityLabel",
            fileName,
            referer,
        ) { app.onlineCache.openCachedStream(url) }
    }

    private fun downloadFileSpec(downloadable: Track): Triple<String, String, String?> {
        val extension = downloadable.audioExtension
            ?.lowercase()
            ?.replace(Regex("[^a-z0-9]"), "")
            ?.takeIf(String::isNotBlank)
            ?: "mp3"
        val qualityLabel = downloadable.resolvedQuality?.label ?: downloadable.resolvedQqQuality?.label ?: settings.value.quality.label
        val fileName = "${downloadable.artist} - ${downloadable.title}.$extension"
        val referer = when (downloadable.source) {
            Track.Source.NETEASE -> "https://music.163.com/"
            Track.Source.QQ -> "https://y.qq.com/"
            else -> null
        }
        return Triple(fileName, qualityLabel, referer)
    }

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        val current = settings.value
        val next = transform(current)
        if (next.cacheLimitBytes != current.cacheLimitBytes) {
            app.onlineCache.reconfigure(next.cacheLimitBytes)
        }
        viewModelScope.launch { settingsRepository.update { transform(it) } }
    }

    /** 在线试听缓存占用字节数（仅当前会话实例；未初始化前为 0）。 */
    val onlineCacheSpace: Long
        get() = app.onlineCache.spaceBytes

    /** 一键清空在线试听缓存（不影响已下载音乐）。 */
    fun clearOnlineCache() {
        app.onlineCache.clearAll()
    }

    override fun onCleared() {
        qrLoginJob?.cancel()
        playback.release()
        super.onCleared()
    }
}
