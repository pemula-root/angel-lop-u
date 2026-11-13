package com.example.innertube.parser

import com.example.core.data.model.TrackEntity
import com.example.innertube.InnerTubeAlbum
import com.example.innertube.InnerTubeArtist
import com.example.innertube.InnerTubePlaylist
import com.example.innertube.SearchResultGroup
import org.json.JSONObject

object SearchParser {

    fun parseSearch(root: JSONObject, query: String): SearchResultGroup {
        val songs = mutableListOf<TrackEntity>()
        var topArtist: InnerTubeArtist? = null
        val artists = mutableListOf<InnerTubeArtist>()
        val albums = mutableListOf<InnerTubeAlbum>()
        val playlists = mutableListOf<InnerTubePlaylist>()

        val tabs = root.optJSONObject("contents")
            ?.optJSONObject("tabbedSearchResultsRenderer")
            ?.optJSONArray("tabs") ?: return SearchResultGroup()

        if (tabs.length() == 0) return SearchResultGroup()
        val sections = tabs.optJSONObject(0)
            ?.optJSONObject("tabRenderer")
            ?.optJSONObject("content")
            ?.optJSONObject("sectionListRenderer")
            ?.optJSONArray("contents") ?: return SearchResultGroup()

        for (s in 0 until sections.length()) {
            val sec = sections.optJSONObject(s) ?: continue

            // 1. Check musicCardShelfRenderer (Top Result)
            val card = sec.optJSONObject("musicCardShelfRenderer")
            if (card != null) {
                val title = BrowseParser.extractRunsText(card.optJSONObject("title"))
                val subtitle = BrowseParser.extractRunsText(card.optJSONObject("subtitle"))
                val thumbnail = BrowseParser.extractThumbnail(card.optJSONObject("thumbnail"))
                val browseId = card.optJSONObject("title")
                    ?.optJSONArray("runs")
                    ?.optJSONObject(0)
                    ?.optJSONObject("navigationEndpoint")
                    ?.optJSONObject("browseEndpoint")
                    ?.optString("browseId") ?: ""

                if (subtitle.contains("Artist", ignoreCase = true) || browseId.startsWith("UC")) {
                    topArtist = InnerTubeArtist(
                        id = browseId.ifBlank { "artist_${title.hashCode()}" },
                        name = title,
                        bannerUrl = BrowseParser.getHighResThumbnail(thumbnail),
                        subscribers = subtitle
                    )
                }
            }

            // 2. Check itemSectionRenderer contents
            val itemSec = sec.optJSONObject("itemSectionRenderer")
            val itemContents = itemSec?.optJSONArray("contents")
            if (itemContents != null) {
                for (i in 0 until itemContents.length()) {
                    val itObj = itemContents.optJSONObject(i) ?: continue

                    // responsive list item
                    val respItem = itObj.optJSONObject("musicResponsiveListItemRenderer")
                    if (respItem != null) {
                        parseItemByType(respItem, songs, artists, albums, playlists)
                    }

                    // music shelf inside itemSection
                    val musicShelf = itObj.optJSONObject("musicShelfRenderer")
                    val shelfContents = musicShelf?.optJSONArray("contents")
                    if (shelfContents != null) {
                        for (k in 0 until shelfContents.length()) {
                            val shelfItem = shelfContents.optJSONObject(k)?.optJSONObject("musicResponsiveListItemRenderer")
                            if (shelfItem != null) {
                                parseItemByType(shelfItem, songs, artists, albums, playlists)
                            }
                        }
                    }
                }
            }

            // 3. Direct musicShelfRenderer on section
            val directShelf = sec.optJSONObject("musicShelfRenderer")
            val directContents = directShelf?.optJSONArray("contents")
            if (directContents != null) {
                for (i in 0 until directContents.length()) {
                    val respItem = directContents.optJSONObject(i)?.optJSONObject("musicResponsiveListItemRenderer")
                    if (respItem != null) {
                        parseItemByType(respItem, songs, artists, albums, playlists)
                    }
                }
            }
        }

        // If topArtist is not set from card, set it from the first artist result matching query
        if (topArtist == null && artists.isNotEmpty()) {
            topArtist = artists.firstOrNull { it.name.contains(query, ignoreCase = true) } ?: artists.first()
        }

        return SearchResultGroup(
            topResultArtist = topArtist,
            songs = songs.distinctBy { it.id },
            albums = albums.distinctBy { it.id },
            artists = artists.distinctBy { it.id },
            playlists = playlists.distinctBy { it.id }
        )
    }

    private fun parseItemByType(
        item: JSONObject,
        songs: MutableList<TrackEntity>,
        artists: MutableList<InnerTubeArtist>,
        albums: MutableList<InnerTubeAlbum>,
        playlists: MutableList<InnerTubePlaylist>
    ) {
        val flexColumns = item.optJSONArray("flexColumns") ?: return
        if (flexColumns.length() < 2) return

        val titleCol = flexColumns.optJSONObject(0)
            ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
            ?.optJSONObject("text")
        val title = BrowseParser.extractRunsText(titleCol)

        val subtitleCol = flexColumns.optJSONObject(1)
            ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
            ?.optJSONObject("text")
        val subtitle = BrowseParser.extractRunsText(subtitleCol)

        val titleRuns = titleCol?.optJSONArray("runs")
        val titleNav = if (titleRuns != null && titleRuns.length() > 0) titleRuns.optJSONObject(0)?.optJSONObject("navigationEndpoint") else null
        val browseId = titleNav?.optJSONObject("browseEndpoint")?.optString("browseId") ?: ""
        var videoId = titleNav?.optJSONObject("watchEndpoint")?.optString("videoId") ?: ""

        if (videoId.isBlank()) {
            videoId = item.optJSONObject("playlistItemData")?.optString("videoId") ?: ""
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

        val thumbnail = BrowseParser.extractThumbnail(item.optJSONObject("thumbnail"))
        val highRes = BrowseParser.getHighResThumbnail(thumbnail)

        when {
            // Artist
            subtitle.contains("Artist", ignoreCase = true) || browseId.startsWith("UC") -> {
                artists.add(
                    InnerTubeArtist(
                        id = browseId.ifBlank { "artist_${title.hashCode()}" },
                        name = title,
                        bannerUrl = highRes,
                        subscribers = subtitle
                    )
                )
            }
            // Album
            subtitle.contains("Album", ignoreCase = true) || subtitle.contains("EP", ignoreCase = true) || browseId.startsWith("MPREb") -> {
                albums.add(
                    InnerTubeAlbum(
                        id = browseId.ifBlank { "alb_${title.hashCode()}" },
                        title = title,
                        artist = subtitle.split("•").getOrNull(1)?.trim() ?: subtitle,
                        year = subtitle.split("•").getOrNull(2)?.trim() ?: "Album",
                        coverUrl = highRes
                    )
                )
            }
            // Playlist
            subtitle.contains("Playlist", ignoreCase = true) || browseId.startsWith("VL") || browseId.startsWith("RDCLAK") -> {
                playlists.add(
                    InnerTubePlaylist(
                        id = browseId.ifBlank { "pl_${title.hashCode()}" },
                        title = title,
                        author = subtitle.split("•").getOrNull(1)?.trim() ?: "YouTube Music",
                        trackCount = subtitle.split("•").getOrNull(2)?.trim() ?: "Playlist",
                        coverUrl = highRes
                    )
                )
            }
            // Song / Video
            videoId.isNotBlank() || subtitle.contains("Song", ignoreCase = true) || subtitle.contains("Video", ignoreCase = true) -> {
                val parsedTrack = BrowseParser.parseResponsiveListItem(item)
                if (parsedTrack != null) {
                    songs.add(parsedTrack)
                }
            }
        }
    }
}
