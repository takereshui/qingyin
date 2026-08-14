package im.molan.music.model

import android.net.Uri

enum class PlaybackMode { LOOP, SINGLE, SHUFFLE }

data class Track(
    val id: String,
    val title: String,
    val artist: String = "未知歌手",
    val album: String = "",
    val durationMs: Long = 0L,
    val uri: Uri? = null,
    val artworkUri: Uri? = null,
    val source: Source = Source.LOCAL,
    val remoteUrl: String? = null,
    /** 网易云接口确认的实际音质；下载任务只接受与当前设置完全一致的音源。 */
    val resolvedQuality: AppSettings.Quality? = null,
    /** 网易云接口返回的实际容器格式，例如 mp3、flac 或 m4a。 */
    val audioExtension: String? = null,
    /** QQ 音乐接口确认的实际音质；其质量集合与网易云完全独立。 */
    val resolvedQqQuality: AppSettings.QqQuality? = null,
    val qqMid: String? = null,
    /** 扫描或下载时保留的原始文件名，仅用于本地音源匹配，绝不用于展示。 */
    val localFileName: String? = null,
    /** 线上链路解析成功的时间戳；用于判断持久化地址是否过期。 */
    val resolvedAt: Long = 0L,
) {
    enum class Source { LOCAL, DOWNLOADED, NETEASE, QQ }
}

data class LyricLine(
    val timeMs: Long,
    val text: String,
    val translation: String? = null,
)

data class DownloadEntry(
    val id: Long,
    val title: String,
    val artist: String,
    val status: Status,
    val bytesDownloaded: Long,
    val totalBytes: Long,
    val fileName: String,
    val errorMessage: String? = null,
) {
    enum class Status { QUEUED, DOWNLOADING, PAUSED, COMPLETED, FAILED, MISSING }
}

data class PlaylistSummary(
    val id: String,
    val name: String,
    val coverUri: Uri? = null,
    val trackCount: Int = 0,
    val creator: String = "",
    val source: Track.Source = Track.Source.NETEASE,
)

data class PlaylistDetail(
    val summary: PlaylistSummary,
    val tracks: List<Track>,
)

data class NcmQrLoginState(
    val stage: Stage = Stage.IDLE,
    val qrImage: String = "",
    val message: String = "未登录",
) {
    enum class Stage { IDLE, LOADING, READY, SCANNED, SUCCESS, ERROR }
}

data class PlayerSnapshot(
    val queue: List<Track> = emptyList(),
    val currentIndex: Int = -1,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val playbackMode: PlaybackMode = PlaybackMode.LOOP,
    val isLoading: Boolean = false,
) {
    val current: Track? get() = queue.getOrNull(currentIndex)
}

data class AppSettings(
    val darkTheme: Boolean = false,
    /** 网易云在线试听/缓存音质（线上辅助，省流量）。 */
    val streamQuality: Quality = Quality.EXHIGH,
    /** 网易云下载音质：保留旧 quality 字段以兼容既有 DataStore 配置。 */
    val quality: Quality = Quality.LOSSLESS,
    /** QQ 音乐在线试听音质。 */
    val streamQqQuality: QqQuality = QqQuality.K320,
    /** QQ 音乐下载音质：使用 ChKSz QQ 接口的原生 size 参数，绝不混用网易云 level。 */
    val qqQuality: QqQuality = QqQuality.FLAC,
    /** 在线试听缓存容量上限（字节），默认 2GB；缓存与下载完全分离。 */
    val cacheLimitBytes: Long = 2L * 1024 * 1024 * 1024,
    val ncmcBaseUrl: String = "https://music.mcseekeri.com",
    val backupNcmcBaseUrl: String = "",
    val useBackupNcmc: Boolean = false,
    val chkszBaseUrl: String = "https://api.chksz.com",
    val useChkszBackup: Boolean = false,
    val chkszApiKey: String = "",
    /** 旧版本单目录配置，保留用于迁移兼容。 */
    val customFolderUri: String = "",
    /** 用户选择的多个本地音乐根目录，按选择顺序持久化。 */
    val customFolderUris: List<String> = emptyList(),
    /** 用户通过 SAF 选择的下载目录 treeUri；为空时使用默认公共 Music/轻音下载。 */
    val downloadFolderUri: String = "",
    val ncmCookie: String = "",
    val ncmNickname: String = "",
    val ncmUserId: Long = 0L,
    /** 用户主动导入的公开歌单 ID；详情本身保存在歌单磁盘缓存中。 */
    val importedPlaylistIds: List<String> = emptyList(),
    /** 首页横幅右侧显示的用户自选歌单；为空时显示“选择歌单”入口。 */
    val homePlaylistId: String = "",
) {
    enum class Quality(val wireValue: String, val label: String) {
        STANDARD("standard", "标准 · 128K"),
        HIGH("higher", "较高 · 192K"),
        EXHIGH("exhigh", "超清 · 320K"),
        LOSSLESS("lossless", "无损 FLAC"),
        HIRES("hires", "高解析 Hi-Res"),
        JYMASTER("jymaster", "极高 JY Master")
    }

    enum class QqQuality(val wireValue: String, val label: String) {
        K128("128k", "QQ 标准 · 128K"),
        K320("320k", "QQ 高品质 · 320K"),
        FLAC("flac", "QQ 无损 FLAC"),
        HIRES("hires", "QQ 高解析 Hi-Res"),
        MASTER("master", "QQ 臻品母带"),
    }
}
