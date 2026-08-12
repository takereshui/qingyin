package im.molan.music.playback

import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import im.molan.music.model.AppSettings
import im.molan.music.model.Track

internal const val EXTRA_SOURCE = "qingyin_source"
internal const val EXTRA_ALBUM = "qingyin_album"
internal const val EXTRA_QQ_MID = "qingyin_qq_mid"
internal const val EXTRA_NCM_QUALITY = "qingyin_ncm_quality"
internal const val EXTRA_QQ_QUALITY = "qingyin_qq_quality"
internal const val EXTRA_AUDIO_EXTENSION = "qingyin_audio_extension"

fun Track.toMediaItem(): MediaItem {
    val extras = Bundle().apply {
        putString(EXTRA_SOURCE, source.name)
        putString(EXTRA_ALBUM, album)
        putString(EXTRA_QQ_MID, qqMid)
        putString(EXTRA_NCM_QUALITY, resolvedQuality?.wireValue)
        putString(EXTRA_QQ_QUALITY, resolvedQqQuality?.wireValue)
        putString(EXTRA_AUDIO_EXTENSION, audioExtension)
    }
    val sourceUri = uri ?: remoteUrl?.let(android.net.Uri::parse)
        ?: error("曲目没有可播放地址：$id")
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
        uri = localConfiguration?.uri?.takeIf { source == Track.Source.LOCAL || source == Track.Source.DOWNLOADED },
        artworkUri = metadata.artworkUri,
        source = source,
        remoteUrl = localConfiguration?.uri?.toString()?.takeIf { source == Track.Source.NETEASE || source == Track.Source.QQ },
        resolvedQuality = AppSettings.Quality.entries.firstOrNull { it.wireValue == metadata.extras?.getString(EXTRA_NCM_QUALITY) },
        resolvedQqQuality = AppSettings.QqQuality.entries.firstOrNull { it.wireValue == metadata.extras?.getString(EXTRA_QQ_QUALITY) },
        audioExtension = metadata.extras?.getString(EXTRA_AUDIO_EXTENSION),
        qqMid = metadata.extras?.getString(EXTRA_QQ_MID),
    )
}
