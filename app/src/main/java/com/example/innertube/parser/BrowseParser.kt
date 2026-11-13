package com.example.innertube.parser

import com.example.core.data.model.TrackEntity
import org.json.JSONObject

object BrowseParser {

    fun parseHomeTracks(root: JSONObject): List<TrackEntity> {
        val tracks = mutableListOf<TrackEntity>()
        val tabs = root.optJSONObject("contents")
            ?.optJSONObject("singleColumnBrowseResultsRenderer")
            ?.optJSONArray("tabs") ?: return emptyList()

        if (tabs.length() == 0) return emptyList()
        val sections = tabs.optJSONObject(0)
            ?.optJSONObject("tabRenderer")
            ?.optJSONObject("content")
            ?.optJSONObject("sectionListRenderer")
            ?.optJSONArray("contents") ?: return emptyList()

        for (i in 0 until sections.length()) {
            val section = sections.optJSONObject(i) ?: continue

            // Quick picks / Trending carousel
            val carousel = section.optJSONObject("musicCarouselShelfRenderer")
            if (carousel != null) {
                val items = carousel.optJSONArray("contents") ?: continue
                for (j in 0 until items.length()) {
                    val item = items.optJSONObject(j) ?: continue
                    val respItem = item.optJSONObject("musicResponsiveListItemRenderer")
                    if (respItem != null) {
                        parseResponsiveListItem(respItem)?.let { tracks.add(it) }
                    } else {
                        val twoRow = item.optJSONObject("musicTwoRowItemRenderer")
                        if (twoRow != null) {
                            parseTwoRowItem(twoRow)?.let { tracks.add(it) }
                        }
                    }
                }
            }
        }
        return tracks.distinctBy { it.id }
    }

    fun parseChartsTracks(root: JSONObject): List<TrackEntity> {
        val tracks = mutableListOf<TrackEntity>()
        val tabs = root.optJSONObject("contents")
            ?.optJSONObject("singleColumnBrowseResultsRenderer")
            ?.optJSONArray("tabs") ?: return emptyList()

        if (tabs.length() == 0) return emptyList()
        val sections = tabs.optJSONObject(0)
            ?.optJSONObject("tabRenderer")
            ?.optJSONObject("content")
            ?.optJSONObject("sectionListRenderer")
            ?.optJSONArray("contents") ?: return emptyList()

        for (i in 0 until sections.length()) {
            val section = sections.optJSONObject(i) ?: continue

            val carousel = section.optJSONObject("musicCarouselShelfRenderer")
            if (carousel != null) {
                val items = carousel.optJSONArray("contents") ?: continue
                for (j in 0 until items.length()) {
                    val item = items.optJSONObject(j) ?: continue
                    item.optJSONObject("musicResponsiveListItemRenderer")?.let {
                        parseResponsiveListItem(it)?.let { trk -> tracks.add(trk) }
                    }
                }
            }

            val musicShelf = section.optJSONObject("musicShelfRenderer")
            if (musicShelf != null) {
                val items = musicShelf.optJSONArray("contents") ?: continue
                for (j in 0 until items.length()) {
                    val item = items.optJSONObject(j) ?: continue
                    item.optJSONObject("musicResponsiveListItemRenderer")?.let {
                        parseResponsiveListItem(it)?.let { trk -> tracks.add(trk) }
                    }
                }
            }
        }
        return tracks.distinctBy { it.id }
    }

    fun parseResponsiveListItem(item: JSONObject): TrackEntity? {
        val flexColumns = item.optJSONArray("flexColumns") ?: return null
        if (flexColumns.length() < 2) return null

        val titleCol = flexColumns.optJSONObject(0)
            ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
            ?.optJSONObject("text")
        val title = extractRunsText(titleCol)

        val subtitleCol = flexColumns.optJSONObject(1)
            ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
            ?.optJSONObject("text")
        val subtitleRuns = subtitleCol?.optJSONArray("runs")

        // Subjudul hasil pencarian berbentuk "Lagu • Artis • Album • 4:16": baris pertama bisa berupa label tipe,
        // jadi artis diambil dari bagian yang tertaut ke halaman artis, bukan dari bagian pertama.
        var artist = ""
        var album = "Single"
        var durationMs = 0L // 0 = tidak diketahui (jangan memakai angka karangan)

        if (subtitleRuns != null && subtitleRuns.length() > 0) {
            val typeLabels = setOf(
                "song", "video", "lagu", "single", "ep", "album", "playlist", "episode", "artist", "artis",
                "podcast", "profile", "profil", "musik", "music"
            )
            val plain = mutableListOf<String>()
            for (r in 0 until subtitleRuns.length()) {
                val run = subtitleRuns.optJSONObject(r) ?: continue
                val runText = run.optString("text").trim()
                if (runText.isEmpty() || runText == "•") continue

                val browse = run.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")
                val browseId = browse?.optString("browseId").orEmpty()
                val pageType = browse?.optJSONObject("browseEndpointContextSupportedConfigs")
                    ?.optJSONObject("browseEndpointContextMusicConfig")
                    ?.optString("pageType").orEmpty()

                when {
                    runText.contains(":") && runText.length <= 8 && runText.all { it.isDigit() || it == ':' } ->
                        durationMs = parseDuration(runText)
                    pageType.contains("ALBUM") || browseId.startsWith("MPRE") ->
                        if (album == "Single") album = runText
                    pageType.contains("ARTIST") || browseId.startsWith("UC") ->
                        if (artist.isEmpty()) artist = runText
                    else -> plain.add(runText)
                }
            }
            if (artist.isEmpty()) {
                artist = plain.firstOrNull {
                    it.lowercase() !in typeLabels &&
                        !it.contains("play", ignoreCase = true) &&
                        !it.contains("tayang", ignoreCase = true) &&
                        !it.contains("putar", ignoreCase = true)
                }.orEmpty()
            }
        }
        if (artist.isEmpty()) artist = "Artist"

        var videoId = item.optJSONObject("playlistItemData")?.optString("videoId") ?: ""
        if (videoId.isBlank()) {
            val titleRuns = titleCol?.optJSONArray("runs")
            if (titleRuns != null && titleRuns.length() > 0) {
                videoId = titleRuns.optJSONObject(0)
                    ?.optJSONObject("navigationEndpoint")
                    ?.optJSONObject("watchEndpoint")
                    ?.optString("videoId") ?: ""
            }
        }
        if (videoId.isBlank()) {
            val overlay = item.optJSONObject("overlay")
                ?.optJSONObject("musicItemThumbnailOverlayRenderer")
                ?.optJSONObject("content")
                ?.optJSONObject("musicPlayButtonRenderer")
                ?.optJSONObject("playNavigationEndpoint")
                ?.optJSONObject("watchEndpoint")
            videoId = overlay?.optString("videoId") ?: ""
        }

        if (title.isBlank() || videoId.isBlank()) return null

        val thumbnail = extractThumbnail(item.optJSONObject("thumbnail"))
        val highResCover = getHighResThumbnail(thumbnail)

        return TrackEntity(
            id = videoId,
            title = title,
            artist = artist,
            album = album,
            durationMs = durationMs,
            coverUrl = highResCover,
            streamUrl = "",
            videoId = videoId,
            genre = "Music",
            dominantColor = 0xFF27272A.toInt() // Neutral dark default, dynamically replaced by palette
        )
    }

    private fun parseTwoRowItem(item: JSONObject): TrackEntity? {
        val title = extractRunsText(item.optJSONObject("title"))
        val subtitle = extractRunsText(item.optJSONObject("subtitle"))
        val navEndpoint = item.optJSONObject("navigationEndpoint")?.optJSONObject("watchEndpoint")
        val videoId = navEndpoint?.optString("videoId") ?: ""

        if (title.isBlank() || videoId.isBlank()) return null

        val thumbnail = extractThumbnail(item.optJSONObject("thumbnailRenderer"))
        return TrackEntity(
            id = videoId,
            title = title,
            artist = subtitle.split("•").firstOrNull()?.trim() ?: subtitle,
            album = "Single",
            durationMs = 0L,
            coverUrl = getHighResThumbnail(thumbnail),
            streamUrl = "",
            videoId = videoId,
            genre = "Music",
            dominantColor = 0xFF27272A.toInt()
        )
    }

    private fun parseDuration(text: String): Long {
        val parts = text.split(":")
        return if (parts.size == 2) {
            val min = parts[0].trim().toLongOrNull() ?: 0L
            val sec = parts[1].trim().toLongOrNull() ?: 0L
            (min * 60 + sec) * 1000L
        } else if (parts.size == 3) {
            val hr = parts[0].trim().toLongOrNull() ?: 0L
            val min = parts[1].trim().toLongOrNull() ?: 0L
            val sec = parts[2].trim().toLongOrNull() ?: 0L
            (hr * 3600 + min * 60 + sec) * 1000L
        } else {
            0L
        }
    }

    fun extractRunsText(obj: JSONObject?): String {
        if (obj == null) return ""
        val runs = obj.optJSONArray("runs") ?: return ""
        return (0 until runs.length()).joinToString("") { runs.optJSONObject(it)?.optString("text") ?: "" }
    }

    fun extractThumbnail(obj: JSONObject?): String {
        if (obj == null) return ""
        val thumbs = obj.optJSONObject("musicThumbnailRenderer")
            ?.optJSONObject("thumbnail")
            ?.optJSONArray("thumbnails")
            ?: obj.optJSONArray("thumbnails")
            ?: return ""
        if (thumbs.length() == 0) return ""
        val last = thumbs.optJSONObject(thumbs.length() - 1)
        return last?.optString("url") ?: ""
    }

    fun getHighResThumbnail(url: String): String {
        if (url.isBlank()) return ""
        return url.replace(Regex("=w\\d+-h\\d+.*"), "=w800-h800-l90-rj")
            .replace(Regex("=s\\d+.*"), "=s800")
    }
}
