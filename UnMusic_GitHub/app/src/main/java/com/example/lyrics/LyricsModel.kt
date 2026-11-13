package com.example.lyrics

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*

data class LyricWord(
    val word: String,
    val startMs: Long,
    val durationMs: Long
)

data class LyricLine(
    val timeMs: Long,
    val text: String,
    val words: List<LyricWord> = emptyList(),
    /** Penanda jeda instrumental (ditampilkan sebagai titik-titik). */
    val gap: Boolean = false
)

enum class LyricsFollowState {
    FOLLOWING,
    USER_SCROLLING,
    DETACHED
}

@Stable
class LyricsState(val listState: LazyListState) {
    var follow by mutableStateOf(LyricsFollowState.FOLLOWING)
        private set

    var programmaticScroll by mutableStateOf(false)
        private set

    fun onUserScrollStart() {
        if (!programmaticScroll && follow != LyricsFollowState.USER_SCROLLING) {
            follow = LyricsFollowState.USER_SCROLLING
        }
    }

    fun onUserScrollEnd() {
        if (follow == LyricsFollowState.USER_SCROLLING) {
            follow = LyricsFollowState.DETACHED
        }
    }

    suspend fun resync(activeIndex: Int) {
        follow = LyricsFollowState.FOLLOWING
        scrollToActive(activeIndex, animate = true)
    }

    suspend fun scrollToActive(index: Int, animate: Boolean) {
        if (index < 0) return
        programmaticScroll = true
        try {
            // Anchor active line ~35% from the top of the viewport
            val offset = -180
            if (animate) {
                listState.animateScrollToItem(index, offset)
            } else {
                listState.scrollToItem(index, offset)
            }
        } finally {
            programmaticScroll = false
        }
    }

    fun resetToFollowing() {
        follow = LyricsFollowState.FOLLOWING
    }
}

object LyricsParser {
    private val TIME_TAG = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
    private val OFFSET_TAG = Regex("""\[offset:\s*([+-]?\d+)\s*]""", RegexOption.IGNORE_CASE)

    /** Gabungkan LRC dengan data per kata (bila ada) tanpa mengubah urutan baris. */
    fun parse(lrcText: String, syncedJson: String): List<LyricLine> {
        if (lyricsLookLikeHtml(lrcText)) return emptyList()
        if (syncedJson.isNotBlank()) {
            val fromJson = TtmlParser.fromJson(syncedJson)
            if (fromJson.isNotEmpty()) return decorate(fromJson)
        }
        return decorate(parseLrc(lrcText))
    }

    private val CREDIT_LINE = Regex(
        "^(?:lyrics?|words|music|written|composed|produced|arranged|mixed|mastered|performed|vocals?|penulis|pencipta|lirik)" +
            "\\s*(?:&|and|,|/)?\\s*(?:music|lyrics?)?\\s*(?:by|oleh)\\s*[:\\-]?\\s*.+$",
        RegexOption.IGNORE_CASE
    )

    /** Perkiraan kapan satu baris selesai dinyanyikan, dibatasi oleh awal baris berikutnya. */
    fun lineEndMs(line: LyricLine, nextTimeMs: Long): Long {
        val estimated = (line.text.length * 90L).coerceIn(1500L, 12000L)
        return maxOf(line.timeMs + 150L, minOf(line.timeMs + estimated, nextTimeMs))
    }

    /**
     * Merapikan lirik tersinkron: membuang baris kredit di awal/akhir ("Written by ...") dan menyisipkan
     * penanda jeda instrumental di antara baris yang jaraknya lebih dari 6 detik.
     */
    fun decorate(lines: List<LyricLine>): List<LyricLine> {
        if (!isSynced(lines)) return lines
        val list = lines.toMutableList()
        var removed = 0
        while (list.isNotEmpty() && removed < 4 && CREDIT_LINE.matches(list.first().text.trim())) {
            list.removeAt(0)
            removed++
        }
        removed = 0
        while (list.isNotEmpty() && removed < 4 && CREDIT_LINE.matches(list.last().text.trim())) {
            list.removeAt(list.size - 1)
            removed++
        }
        if (list.size < 2) return list

        val out = ArrayList<LyricLine>(list.size + 8)
        for (i in list.indices) {
            val line = list[i]
            out.add(line)
            val next = list.getOrNull(i + 1) ?: continue
            val end = lineEndMs(line, next.timeMs)
            if (next.timeMs - end > 6000L) {
                out.add(LyricLine(timeMs = end, text = "♪", gap = true))
            }
        }
        return out
    }

    /** True bila semua baris punya waktu. Lirik teks biasa memakai waktu -1 dan tidak boleh disorot per waktu. */
    fun isSynced(lines: List<LyricLine>): Boolean = lines.isNotEmpty() && lines.all { it.timeMs >= 0L }

    private fun toMs(match: MatchResult): Long {
        val min = match.groupValues[1].toLongOrNull() ?: 0L
        val sec = match.groupValues[2].toLongOrNull() ?: 0L
        val frac = match.groupValues[3]
        val ms = when (frac.length) {
            0 -> 0L
            1 -> (frac.toLongOrNull() ?: 0L) * 100
            2 -> (frac.toLongOrNull() ?: 0L) * 10
            else -> frac.take(3).toLongOrNull() ?: 0L
        }
        return min * 60_000L + sec * 1_000L + ms
    }

    fun parseLrc(lrcText: String): List<LyricLine> {
        if (lrcText.isBlank()) return emptyList()
        // Tag [offset:+500] menggeser semua waktu lebih awal sebesar nilainya (standar LRC)
        val offset = OFFSET_TAG.find(lrcText)?.groupValues?.get(1)?.toLongOrNull() ?: 0L

        val timed = mutableListOf<LyricLine>()
        val plain = mutableListOf<String>()

        for (rawLine in lrcText.lines()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue

            // Satu baris boleh punya beberapa tag waktu: [00:12.00][01:05.00]teks
            val times = mutableListOf<Long>()
            var rest = line
            while (true) {
                val m = TIME_TAG.find(rest)
                if (m == null || m.range.first != 0) break
                times.add(toMs(m))
                rest = rest.substring(m.range.last + 1).trimStart()
            }

            if (times.isNotEmpty()) {
                val text = rest.trim()
                if (text.isNotEmpty()) {
                    for (t in times) timed.add(LyricLine(timeMs = (t - offset).coerceAtLeast(0L), text = text))
                }
            } else if (!line.startsWith("[")) {
                plain.add(line)
            }
        }

        // Teks biasa tidak diberi waktu karangan: ditandai -1 agar ditampilkan statis
        if (timed.isNotEmpty()) return timed.sortedBy { it.timeMs }
        return plain.map { LyricLine(timeMs = -1L, text = it) }
    }

    /**
     * Efficient binary search to find the active lyric line index at a given playback position.
     */
    fun findActiveLineIndex(lines: List<LyricLine>, currentPositionMs: Long): Int {
        if (lines.isEmpty()) return -1
        if (lines.first().timeMs < 0L) return -1
        if (currentPositionMs < lines.first().timeMs) return -1
        if (currentPositionMs >= lines.last().timeMs) return lines.size - 1

        var low = 0
        var high = lines.size - 1
        var result = 0

        while (low <= high) {
            val mid = (low + high) ushr 1
            if (lines[mid].timeMs <= currentPositionMs) {
                result = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return result
    }
}
