package com.example.innertube.parser

import com.example.core.data.model.TrackEntity
import org.json.JSONArray
import org.json.JSONObject

/** Parser halaman album: daftar lagu dari respons `browse`. */
object AlbumParser {

    fun parseTracks(root: JSONObject, albumTitle: String, albumArtist: String, coverUrl: String): List<TrackEntity> {
        val items = mutableListOf<JSONObject>()
        collect(root, "musicResponsiveListItemRenderer", items)

        val out = mutableListOf<TrackEntity>()
        for (item in items) {
            val videoId = item.optJSONObject("playlistItemData")?.optString("videoId").orEmpty()
            if (videoId.isBlank()) continue

            val flex = item.optJSONArray("flexColumns") ?: continue
            val title = runsText(flex, 0)
            if (title.isBlank()) continue

            var trackArtist = ""
            val second = flex.optJSONObject(1)
                ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                ?.optJSONObject("text")
                ?.optJSONArray("runs")
            if (second != null) {
                for (i in 0 until second.length()) {
                    val t = second.optJSONObject(i)?.optString("text").orEmpty().trim()
                    if (t.isBlank() || t == "•" || t.contains("play", ignoreCase = true)) continue
                    if (t.all { it.isDigit() || it == ',' || it == '.' || it == 'M' || it == 'K' }) continue
                    trackArtist = t
                    break
                }
            }

            val durationText = item.optJSONArray("fixedColumns")
                ?.optJSONObject(0)
                ?.optJSONObject("musicResponsiveListItemFixedColumnRenderer")
                ?.optJSONObject("text")
                ?.optJSONArray("runs")
                ?.optJSONObject(0)
                ?.optString("text").orEmpty()

            out.add(
                TrackEntity(
                    id = videoId,
                    title = title,
                    artist = if (trackArtist.isBlank()) albumArtist else trackArtist,
                    album = albumTitle,
                    durationMs = parseDuration(durationText),
                    coverUrl = coverUrl,
                    streamUrl = "",
                    videoId = videoId,
                    genre = "Music",
                    dominantColor = 0xFF27272A.toInt()
                )
            )
        }
        return out.distinctBy { it.id }
    }

    private fun runsText(flex: JSONArray, index: Int): String {
        val runs = flex.optJSONObject(index)
            ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
            ?.optJSONObject("text")
            ?.optJSONArray("runs") ?: return ""
        val sb = StringBuilder()
        for (i in 0 until runs.length()) sb.append(runs.optJSONObject(i)?.optString("text").orEmpty())
        return sb.toString().trim()
    }

    private fun collect(node: Any?, key: String, out: MutableList<JSONObject>) {
        when (node) {
            is JSONObject -> {
                val hit = node.optJSONObject(key)
                if (hit != null) {
                    out.add(hit)
                    return
                }
                val keys = node.keys()
                while (keys.hasNext()) collect(node.opt(keys.next()), key, out)
            }
            is JSONArray -> for (i in 0 until node.length()) collect(node.opt(i), key, out)
        }
    }

    private fun parseDuration(text: String): Long {
        val parts = text.split(":").mapNotNull { it.trim().toLongOrNull() }
        return when (parts.size) {
            2 -> (parts[0] * 60 + parts[1]) * 1000
            3 -> (parts[0] * 3600 + parts[1] * 60 + parts[2]) * 1000
            else -> 0L
        }
    }
}
