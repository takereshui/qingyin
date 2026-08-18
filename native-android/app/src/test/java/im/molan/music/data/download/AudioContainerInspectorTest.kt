package im.molan.music.data.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AudioContainerInspectorTest {
    @Test
    fun `detects ID3 tagged and raw MP3 streams`() {
        assertEquals("mp3", AudioContainerInspector.detect(header(0x49, 0x44, 0x33, 0x04)))
        assertEquals("mp3", AudioContainerInspector.detect(header(0xFF, 0xFB, 0x90, 0x64)))
    }

    @Test
    fun `detects FLAC OGG and WAV containers`() {
        assertEquals("flac", AudioContainerInspector.detect(header(0x66, 0x4C, 0x61, 0x43)))
        assertEquals("ogg", AudioContainerInspector.detect(header(0x4F, 0x67, 0x67, 0x53)))
        assertEquals(
            "wav",
            AudioContainerInspector.detect(header(0x52, 0x49, 0x46, 0x46, 0, 0, 0, 0, 0x57, 0x41, 0x56, 0x45)),
        )
    }

    @Test
    fun `detects M4A MP4 and AAC streams`() {
        assertEquals(
            "m4a",
            AudioContainerInspector.detect(header(0, 0, 0, 0x18, 0x66, 0x74, 0x79, 0x70, 0x4D, 0x34, 0x41, 0x20)),
        )
        assertEquals(
            "mp4",
            AudioContainerInspector.detect(header(0, 0, 0, 0x18, 0x66, 0x74, 0x79, 0x70, 0x69, 0x73, 0x6F, 0x6D)),
        )
        assertEquals("aac", AudioContainerInspector.detect(header(0xFF, 0xF1, 0x50, 0x80)))
    }

    @Test
    fun `keeps unknown or insufficient data unclassified`() {
        assertNull(AudioContainerInspector.detect(header(0xDE, 0xAD, 0xBE, 0xEF)))
        assertNull(AudioContainerInspector.detect(header(0x49, 0x44, 0x33), 3))
    }

    @Test
    fun `only exposes containers with safe tag write support`() {
        assertEquals(
            setOf("mp3", "flac", "m4a", "mp4", "ogg", "wav"),
            AudioContainerInspector.tagWritableExtensions,
        )
    }

    private fun header(vararg values: Int): ByteArray = values.map(Int::toByte).toByteArray()
}
