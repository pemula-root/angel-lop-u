package com.example.innertube.parser

import com.example.core.data.model.TrackEntity
import org.json.JSONArray
import org.json.JSONObject

data class RadioPage(val tracks: List<TrackEntity>, val continuation: String?)

/** Parser respons endpoint `next` (radio / antrean berikutnya). */
object RadioParser {

    fun parse(root: JSONObject): RadioPage {
        val renderers = mutableListOf<JSONObject>()
        collect(root, "playlistPanelVideoRenderer", renderers)
        val tracks = renderers.mapNotNull { toTrack(it) }.distinctBy { it.id }
        return RadioPage(tracks, findContinuation(root))
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

    private fun findContinuation(node: Any?): String? {
        when (node) {
            is JSONObject -> {
                for (k in listOf("nextRadioContinuationData", "nextContinuationData")) {
                    val c = node.optJSONObject(k)?.optString("continuation")
                    if (!c.isNullOrBlank()) return c
                }
                val keys = node.keys()
                while (keys.hasNext()) {
                    val r = findContinuation(node.opt(keys.next()))
                    if (r != null) return r
                }
            }
            is JSONArray -> for (i in 0 until node.length()) {
                val r = findContinuation(node.opt(i))
                if (r != null) return r
            }
        }
        return null
    }

    private fun toTrack(r: JSONObject): TrackEntity? {
        val videoId = r.optString("videoId")
        val title = r.optJSONObject("title")?.optJSONArray("runs")?.optJSONObject(0)?.optString("text").orEmpty()
        if (videoId.isBlank() || title.isBlank()) return null

        val runs = r.optJSONObject("longBylineText")?.optJSONArray("runs")
        var artist = ""
        var album = ""
        if (runs != null) {
            for (i in 0 until runs.length()) {
                val run = runs.optJSONObject(i) ?: continue
                val text = run.optString("text")
                if (text.isBlank() || text.trim() == "•") continue
                val pageType = run.optJSONObject("navigationEndpoint")
                    ?.optJSONObject("browseEndpoint")
                    ?.optJSONObject("browseEndpointContextSupportedConfigs")
                    ?.optJSONObject("browseEndpointContextMusicConfig")
                    ?.optString("pageType").orEmpty()
                when {
                    pageType.contains("ALBUM") && album.isBlank() -> album = text
                    artist.isBlank() && !pageType.contains("ALBUM") && !text.matches(Regex("\\d{4}")) -> artist = text
                }
            }
        }
        if (artist.isBlank()) artist = "Artis"

        val durationText = r.optJSONObject("lengthText")?.optJSONArray("runs")?.optJSONObject(0)?.optString("text").orEmpty()
        val thumbs = r.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
        val rawCover = if (thumbs != null && thumbs.length() > 0) thumbs.optJSONObject(thumbs.length() - 1)?.optString("url").orEmpty() else ""
        val cover = rawCover.replace(Regex("=w\\d+-h\\d+.*$"), "=w544-h544-l90-rj")

        return TrackEntity(
            id = videoId,
            title = title,
            artist = artist,
            album = album,
            durationMs = parseDuration(durationText),
            coverUrl = cover,
            streamUrl = "",
            videoId = videoId,
            genre = "Music",
            dominantColor = 0xFF27272A.toInt()
        )
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
