package im.molan.music.data.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LrcParserTest {
    @Test
    fun parsesMultipleTimestampsAndTranslation() {
        val lines = LrcParser.parse(
            "[00:01.20][00:03.400]第一句\n[00:06:50]第二句",
            "[00:01.20]First line\n[00:06.50]Second line",
        )
        assertEquals(3, lines.size)
        assertEquals(1_200L, lines[0].timeMs)
        assertEquals("第一句", lines[0].text)
        assertEquals("First line", lines[0].translation)
        assertEquals(3_400L, lines[1].timeMs)
        assertNull(lines[1].translation)
        assertEquals(6_500L, lines[2].timeMs)
    }

    @Test
    fun findsCurrentLineByBinarySearch() {
        val lines = LrcParser.parse("[00:01.00]一\n[00:05.00]二\n[00:09.00]三")
        assertEquals(-1, LrcParser.indexAt(lines, 500L))
        assertEquals(0, LrcParser.indexAt(lines, 1_000L))
        assertEquals(1, LrcParser.indexAt(lines, 6_000L))
        assertEquals(2, LrcParser.indexAt(lines, 10_000L))
    }
}
