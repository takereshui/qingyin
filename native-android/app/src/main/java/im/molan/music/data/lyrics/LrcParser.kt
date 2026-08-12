package im.molan.music.data.lyrics

import im.molan.music.model.LyricLine

object LrcParser {
    private val timestamp = Regex("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?]")

    fun parse(primary: String?, translation: String? = null): List<LyricLine> {
        val base = parseTimedLines(primary)
        if (base.isEmpty()) return emptyList()
        val translated = parseTimedLines(translation).associateBy { it.first }
        return base.map { (time, text) ->
            LyricLine(timeMs = time, text = text, translation = translated[time]?.second?.takeIf(String::isNotBlank))
        }.sortedBy(LyricLine::timeMs)
    }

    fun indexAt(lines: List<LyricLine>, positionMs: Long): Int {
        var low = 0
        var high = lines.lastIndex
        var answer = -1
        while (low <= high) {
            val mid = (low + high).ushr(1)
            if (lines[mid].timeMs <= positionMs + 80) {
                answer = mid
                low = mid + 1
            } else high = mid - 1
        }
        return answer
    }

    private fun parseTimedLines(raw: String?): List<Pair<Long, String>> {
        if (raw.isNullOrBlank()) return emptyList()
        return buildList {
            raw.lineSequence().forEach { row ->
                val matches = timestamp.findAll(row).toList()
                if (matches.isEmpty()) return@forEach
                val text = row.substring(matches.last().range.last + 1).trim()
                matches.forEach { match ->
                    val minute = match.groupValues[1].toLongOrNull() ?: return@forEach
                    val second = match.groupValues[2].toLongOrNull() ?: return@forEach
                    val fraction = match.groupValues[3].let { digits ->
                        when (digits.length) { 0 -> 0L; 1 -> digits.toLong() * 100; 2 -> digits.toLong() * 10; else -> digits.take(3).toLong() }
                    }
                    add((minute * 60_000L + second * 1_000L + fraction) to text)
                }
            }
        }.distinctBy { it.first }.sortedBy { it.first }
    }
}
