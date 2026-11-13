package com.example.innertube.parser

import com.example.core.data.model.TrackEntity
import com.example.innertube.InnerTubeAlbum
import com.example.innertube.InnerTubeArtist
import org.json.JSONObject

data class ArtistPageData(
    val artist: InnerTubeArtist,
    val songs: List<TrackEntity> = emptyList(),
    val albums: List<InnerTubeAlbum> = emptyList(),
    val similarArtists: List<InnerTubeArtist> = emptyList(),
    val continuationToken: String? = null
)

object ArtistParser {

    fun parseArtistPage(root: JSONObject, fallbackBrowseId: String, fallbackName: String): ArtistPageData {
        val songs = mutableListOf<TrackEntity>()
        val albums = mutableListOf<InnerTubeAlbum>()
        val similar = mutableListOf<InnerTubeArtist>()
        var continuation: String? = null

        var artistName = fallbackName
        var bannerUrl = ""
        var subText = "Artist"

        // Header parsing
        val header = root.optJSONObject("header")?.optJSONObject("musicImmersiveHeaderRenderer")
            ?: root.optJSONObject("header")?.optJSONObject("musicVisualHeaderRenderer")

        if (header != null) {
            val titleStr = BrowseParser.extractRunsText(header.optJSONObject("title"))
            if (titleStr.isNotBlank()) artistName = titleStr
            subText = BrowseParser.extractRunsText(header.optJSONObject("subscriptionButton"))
                .ifBlank { BrowseParser.extractRunsText(header.optJSONObject("description")) }
                .ifBlank { "Artist" }

            bannerUrl = BrowseParser.extractThumbnail(header.optJSONObject("thumbnail"))
            bannerUrl = BrowseParser.getHighResThumbnail(bannerUrl)
        }

        // Section parsing
        val sections = root.optJSONObject("contents")
            ?.optJSONObject("singleColumnBrowseResultsRenderer")
            ?.optJSONArray("tabs")
            ?.optJSONObject(0)
            ?.optJSONObject("tabRenderer")
            ?.optJSONObject("content")
            ?.optJSONObject("sectionListRenderer")
            ?.optJSONArray("contents")

        if (sections != null) {
            for (s in 0 until sections.length()) {
                val sec = sections.optJSONObject(s) ?: continue

                // Check shelf header title (Songs, Albums, Singles, Fans might also like)
                val carousel = sec.optJSONObject("musicCarouselShelfRenderer")
                val shelfHeader = carousel?.optJSONObject("header")
                    ?.optJSONObject("musicCarouselShelfBasicHeaderRenderer")
                    ?.optJSONObject("title")
                val headerTitle = BrowseParser.extractRunsText(shelfHeader).lowercase()

                val carouselItems = carousel?.optJSONArray("contents")
                if (carouselItems != null) {
                    for (i in 0 until carouselItems.length()) {
                        val it = carouselItems.optJSONObject(i) ?: continue

                        val resp = it.optJSONObject("musicResponsiveListItemRenderer")
                        if (resp != null) {
                            BrowseParser.parseResponsiveListItem(resp)?.let { trk ->
                                songs.add(trk.copy(artist = artistName))
                            }
                        }

                        val twoRow = it.optJSONObject("musicTwoRowItemRenderer")
                        if (twoRow != null) {
                            val title = BrowseParser.extractRunsText(twoRow.optJSONObject("title"))
                            val subtitle = BrowseParser.extractRunsText(twoRow.optJSONObject("subtitle"))
                            val thumb = BrowseParser.getHighResThumbnail(BrowseParser.extractThumbnail(twoRow.optJSONObject("thumbnailRenderer")))
                            val nav = twoRow.optJSONObject("navigationEndpoint")
                            val browseId = nav?.optJSONObject("browseEndpoint")?.optString("browseId") ?: ""
                            val videoId = nav?.optJSONObject("watchEndpoint")?.optString("videoId") ?: ""

                            if (headerTitle.contains("album") || headerTitle.contains("single") || browseId.startsWith("MPREb")) {
                                albums.add(InnerTubeAlbum(browseId.ifBlank { "alb_${title.hashCode()}" }, title, artistName, subtitle, thumb))
                            } else if (headerTitle.contains("like") || headerTitle.contains("artist") || browseId.startsWith("UC")) {
                                similar.add(InnerTubeArtist(browseId.ifBlank { "artist_${title.hashCode()}" }, title, thumb, subtitle))
                            } else if (videoId.isNotBlank()) {
                                songs.add(
                                    TrackEntity(
                                        id = videoId,
                                        title = title,
                                        artist = artistName,
                                        album = "Single",
                                        durationMs = 210000L,
                                        coverUrl = thumb,
                                        videoId = videoId,
                                        dominantColor = 0xFF27272A.toInt()
                                    )
                                )
                            }
                        }
                    }
                }

                // Check continuation token
                val continuations = sec.optJSONObject("musicShelfRenderer")?.optJSONArray("continuations")
                if (continuations != null && continuations.length() > 0) {
                    continuation = continuations.optJSONObject(0)
                        ?.optJSONObject("nextContinuationData")
                        ?.optString("continuation")
                }
            }
        }

        val artist = InnerTubeArtist(
            id = fallbackBrowseId,
            name = artistName,
            bannerUrl = bannerUrl,
            subscribers = subText
        )

        return ArtistPageData(
            artist = artist,
            songs = songs.distinctBy { it.id },
            albums = albums.distinctBy { it.id },
            similarArtists = similar.distinctBy { it.id },
            continuationToken = continuation
        )
    }
}
