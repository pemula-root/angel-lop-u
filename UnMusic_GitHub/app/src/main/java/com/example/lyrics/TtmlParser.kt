package com.example.lyrics

import org.json.JSONArray
import org.json.JSONObject

/** Parser TTML ringan (format lirik per kata) menjadi baris + kata. */
object TtmlParser {

    private val pRegex = Regex("<p\\b([^>]*)>(.*?)</p>", setOf(RegexOption.DOT_MATCHES_ALL))
    private val spanRegex = Regex("<span\\b([^>]*)>(.*?)</span>", setOf(RegexOption.DOT_MATCHES_ALL))
    private val tagRegex = Regex("<[^>]+>")

    fun parse(ttml: String): List<LyricLine> {
        val lines = mutableListOf<LyricLine>()
        for (m in pRegex.findAll(ttml)) {
            val attrs = m.groupValues[1]
            val inner = m.groupValues[2]
            val begin = attr(attrs, "begin")?.let { parseTime(it) } ?: continue

            val words = mutableListOf<LyricWord>()
            for (sm in spanRegex.findAll(inner)) {
                val sAttrs = sm.groupValues[1]
                val role = attr(sAttrs, "ttm:role")
                if (role == "x-bg" || role == "x-translation" || role == "x-roman") continue
                val text = decode(sm.groupValues[2].replace(tagRegex, ""))
                if (text.isBlank()) continue
                val sb = attr(sAttrs, "begin")?.let { parseTime(it) } ?: continue
                val se = attr(sAttrs, "end")?.let { parseTime(it) } ?: (sb + 300)
                words.add(LyricWord(text.trim(), sb, (se - sb).coerceAtLeast(0)))
            }
            val text = if (words.isNotEmpty()) {
                words.joinToString(" ") { it.word }
            } else {
                decode(inner.replace(tagRegex, "")).replace(Regex("\\s+"), " ").trim()
            }
            if (text.isNotBlank()) lines.add(LyricLine(begin, text, words))
        }
        return lines.sortedBy { it.timeMs }
    }

    fun toLrc(lines: List<LyricLine>): String {
        val sb = StringBuilder()
        for (l in lines) {
            val total = l.timeMs
            val min = total / 60_000
            val sec = (total % 60_000) / 1000
            val cs = (total % 1000) / 10
            sb.append("[%02d:%02d.%02d]".format(min, sec, cs)).append(l.text).append('\n')
        }
        return sb.toString().trim()
    }

    fun toJson(lines: List<LyricLine>): String {
        val arr = JSONArray()
        for (l in lines) {
            val o = JSONObject().put("t", l.timeMs).put("x", l.text)
            if (l.words.isNotEmpty()) {
                val w = JSONArray()
                l.words.forEach { w.put(JSONArray().put(it.word).put(it.startMs).put(it.durationMs)) }
                o.put("w", w)
            }
            arr.put(o)
        }
        return arr.toString()
    }

    fun fromJson(json: String): List<LyricLine> {
        if (json.isBlank()) return emptyList()
        return try {
            val arr = JSONArray(json)
            val out = ArrayList<LyricLine>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val wArr = o.optJSONArray("w")
                val words = ArrayList<LyricWord>()
                if (wArr != null) {
                    for (j in 0 until wArr.length()) {
                        val w = wArr.getJSONArray(j)
                        words.add(LyricWord(w.getString(0), w.getLong(1), w.getLong(2)))
                    }
                }
                out.add(LyricLine(o.getLong("t"), o.getString("x"), words))
            }
            out
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun attr(attrs: String, name: String): String? {
        val m = Regex("\\b${Regex.escape(name)}\\s*=\\s*\"([^\"]*)\"").find(attrs)
        return m?.groupValues?.get(1)
    }

    private fun parseTime(t: String): Long {
        val s = t.trim()
        if (s.endsWith("ms")) return s.removeSuffix("ms").toDoubleOrNull()?.toLong() ?: 0L
        if (s.endsWith("s")) return ((s.removeSuffix("s").toDoubleOrNull() ?: 0.0) * 1000).toLong()
        val parts = s.split(":")
        var seconds = 0.0
        for (p in parts) seconds = seconds * 60 + (p.toDoubleOrNull() ?: 0.0)
        return (seconds * 1000).toLong()
    }

    private fun decode(s: String): String = s
        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&apos;", "'").replace("&#39;", "'")
}
