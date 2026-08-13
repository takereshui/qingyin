package im.molan.music.playback

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaItem.RequestMetadata
import androidx.media3.common.MediaMetadata
import im.molan.music.model.AppSettings
import im.molan.music.model.Track

internal const val EXTRA_SOURCE = "qingyin_source"
internal const val EXTRA_ALBUM = "qingyin_album"
internal const val EXTRA_QQ_MID = "qingyin_qq_mid"
internal const val EXTRA_NCM_QUALITY = "qingyin_ncm_quality"
internal const val EXTRA_QQ_QUALITY = "qingyin_qq_quality"
internal const val EXTRA_AUDIO_EXTENSION = "qingyin_audio_extension"
internal const val EXTRA_LOCAL_FILE_NAME = "qingyin_local_file_name"
internal const val EXTRA_DURATION = "qingyin_duration"
internal const val EXTRA_RESOLVED_AT = "qingyin_resolved_at"
private const val PENDING_QUEUE_SCHEME = "qingyin-queue"
private const val CHROME_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

fun Track.toMediaItem(): MediaItem {
    val extras = Bundle().apply {
        putString(EXTRA_SOURCE, source.name)
        putString(EXTRA_ALBUM, album)
        putString(EXTRA_QQ_MID, qqMid)
        putString(EXTRA_NCM_QUALITY, resolvedQuality?.wireValue)
        putString(EXTRA_QQ_QUALITY, resolvedQqQuality?.wireValue)
        putString(EXTRA_AUDIO_EXTENSION, audioExtension)
        putString(EXTRA_LOCAL_FILE_NAME, localFileName)
        putLong(EXTRA_DURATION, durationMs)
        putLong(EXTRA_RESOLVED_AT, resolvedAt)
    }
    val sourceUri = uri ?: remoteUrl?.takeIf { it.startsWith("https://") || it.startsWith("http://") }?.let(Uri::parse)
        // 线上歌单可先完整入队；真正播放到该曲目时才由 ViewModel 替换为来源 API 返回的地址。
        // 本地/下载曲目缺地址时不抛异常，先入 pending 队列，播放到它会以“无可用地址”优雅失败。
        ?: Uri.Builder().scheme(PENDING_QUEUE_SCHEME).authority("pending").appendPath(id).build()
    return MediaItem.Builder()
        .setMediaId(id)
        .setUri(sourceUri)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                .setAlbumTitle(album)
                .setArtworkUri(artworkUri)
                .setExtras(extras)
                .build(),
        )
        .setRequestMetadata(RequestMetadata.Builder().build())
        .build()
}

fun MediaItem.toTrack(): Track {
    val metadata = mediaMetadata
    val source = runCatching {
        Track.Source.valueOf(metadata.extras?.getString(EXTRA_SOURCE).orEmpty())
    }.getOrDefault(Track.Source.LOCAL)
    return Track(
        id = mediaId,
        title = metadata.title?.toString().orEmpty().ifBlank { "未知歌曲" },
        artist = metadata.artist?.toString().orEmpty().ifBlank { "未知歌手" },
        album = metadata.extras?.getString(EXTRA_ALBUM).orEmpty(),
        durationMs = metadata.extras?.getLong(EXTRA_DURATION) ?: 0L,
        uri = localConfiguration?.uri?.takeIf { source == Track.Source.LOCAL || source == Track.Source.DOWNLOADED },
        artworkUri = metadata.artworkUri,
        source = source,
        remoteUrl = localConfiguration?.uri?.toString()?.takeIf {
            (source == Track.Source.NETEASE || source == Track.Source.QQ) &&
                (it.startsWith("https://") || it.startsWith("http://"))
        },
        resolvedQuality = AppSettings.Quality.entries.firstOrNull { it.wireValue == metadata.extras?.getString(EXTRA_NCM_QUALITY) },
        resolvedQqQuality = AppSettings.QqQuality.entries.firstOrNull { it.wireValue == metadata.extras?.getString(EXTRA_QQ_QUALITY) },
        audioExtension = metadata.extras?.getString(EXTRA_AUDIO_EXTENSION),
        qqMid = metadata.extras?.getString(EXTRA_QQ_MID),
        localFileName = metadata.extras?.getString(EXTRA_LOCAL_FILE_NAME),
        resolvedAt = metadata.extras?.getLong(EXTRA_RESOLVED_AT) ?: 0L,
    )
}
