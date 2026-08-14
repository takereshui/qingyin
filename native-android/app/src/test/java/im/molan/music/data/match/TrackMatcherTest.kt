package im.molan.music.data.match

import im.molan.music.model.Track
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackMatcherTest {
    @Test
    fun unrelatedTracksDoNotProduceAScoreEvenWhenDurationsAreEqual() {
        val remote = remoteTrack(title = "晴天", artist = "周杰伦", durationMs = 240_000L)
        val local = localTrack(title = "演员", artist = "薛之谦", durationMs = 240_000L)

        assertNull(TrackMatcher.score(remote, local))
    }

    @Test
    fun sameTitleButDifferentArtistDoesNotReachAcceptanceThreshold() {
        val remote = remoteTrack(title = "后来", artist = "刘若英", durationMs = 300_000L)
        val local = localTrack(title = "后来", artist = "张韶涵", durationMs = 300_500L)

        val result = TrackMatcher.score(remote, local)
        assertNotNull(result)
        assertTrue("同名但歌手不同不得达到接受阈值", result!!.score < 88f)
    }

    @Test
    fun exactTitleAndArtistMatchWithinEncodingDurationTolerance() {
        val remote = remoteTrack(title = "青花瓷", artist = "周杰伦", durationMs = 239_000L)
        val local = localTrack(title = "青花瓷", artist = "周杰伦", durationMs = 241_500L)

        assertNotNull(TrackMatcher.score(remote, local))
    }

    private fun remoteTrack(title: String, artist: String, durationMs: Long) = Track(
        id = "ncm:$title",
        title = title,
        artist = artist,
        durationMs = durationMs,
        source = Track.Source.NETEASE,
    )

    private fun localTrack(title: String, artist: String, durationMs: Long) = Track(
        id = "local:$title",
        title = title,
        artist = artist,
        durationMs = durationMs,
        source = Track.Source.LOCAL,
        localFileName = "$artist - $title.mp3",
    )
}
