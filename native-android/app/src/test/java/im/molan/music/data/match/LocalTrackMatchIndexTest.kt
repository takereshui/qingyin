package im.molan.music.data.match

import im.molan.music.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalTrackMatchIndexTest {
    @Test
    fun replacingCandidatesInvalidatesPreviousPositiveMatch() {
        val index = testIndex()
        val remote = remoteTrack("晴天", "周杰伦")
        val originalLocal = localTrack("local:1", "晴天", "周杰伦")
        val replacementLocal = localTrack("local:2", "晴天", "周杰伦")

        index.replace(listOf(originalLocal))
        assertEquals("local:1", index.find(remote)?.local?.id)

        index.replace(listOf(replacementLocal))

        assertEquals("local:2", index.find(remote)?.local?.id)
    }

    @Test
    fun indexDoesNotMatchUnrelatedCandidate() {
        val index = testIndex()
        index.replace(listOf(localTrack("local:1", "演员", "薛之谦")))

        assertNull(index.find(remoteTrack("晴天", "周杰伦")))
    }

    private fun testIndex() = LocalTrackMatchIndex { target, candidates ->
        candidates.mapNotNull { TrackMatcher.score(target, it) }.maxByOrNull(TrackMatcher.Result::score)
    }

    private fun remoteTrack(title: String, artist: String) = Track(
        id = "ncm:$title",
        title = title,
        artist = artist,
        durationMs = 240_000L,
        source = Track.Source.NETEASE,
    )

    private fun localTrack(id: String, title: String, artist: String) = Track(
        id = id,
        title = title,
        artist = artist,
        durationMs = 240_000L,
        source = Track.Source.LOCAL,
        localFileName = "$artist - $title.mp3",
    )
}
