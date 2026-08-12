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
    val qqMid: String? = null,
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
) {
    enum class Status { QUEUED, DOWNLOADING, PAUSED, COMPLETED, FAILED, MISSING }
}

data class PlaylistSummary(
    val id: String,
    val name: String,
    val coverUri: Uri? = null,
    val trackCount: Int = 0,
    val creator: String = "",
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
    val quality: Quality = Quality.EXHIGH,
    val ncmcBaseUrl: String = "https://music.mcseekeri.com",
    val backupNcmcBaseUrl: String = "",
    val useBackupNcmc: Boolean = false,
    val chkszBaseUrl: String = "https://api.chksz.com",
    val useChkszBackup: Boolean = false,
    val chkszApiKey: String = "",
    val customFolderUri: String = "",
    val ncmCookie: String = "",
    val ncmNickname: String = "",
    val ncmUserId: Long = 0L,
) {
    enum class Quality(val wireValue: String, val label: String) {
        STANDARD("standard", "标准 · 128K"),
        HIGH("higher", "较高 · 192K"),
        EXHIGH("exhigh", "超清 · 320K"),
        LOSSLESS("lossless", "无损 FLAC"),
        HIRES("hires", "高解析 Hi-Res"),
        JYMASTER("jymaster", "极高 JY Master")
    }
}
