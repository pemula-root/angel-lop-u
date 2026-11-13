package com.example.podcast

import com.example.core.data.db.AppDatabase
import com.example.core.data.model.PodcastEntity
import com.example.core.data.model.PodcastEpisodeEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.util.concurrent.TimeUnit

class PodcastManager(private val database: AppDatabase) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun subscribeFeed(feedUrl: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(feedUrl).build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return@withContext false
            val xml = response.body?.string() ?: return@withContext false

            val factory = XmlPullParserFactory.newInstance()
            val parser = factory.newPullParser()
            parser.setInput(StringReader(xml))

            var title = ""
            var author = ""
            var description = ""
            var coverUrl = ""
            val episodes = mutableListOf<PodcastEpisodeEntity>()

            var eventType = parser.eventType
            var inItem = false
            var currentItemTitle = ""
            var currentAudioUrl = ""
            var currentDuration = 0L

            while (eventType != XmlPullParser.END_DOCUMENT) {
                val tagName = parser.name
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        if (tagName.equals("item", ignoreCase = true)) {
                            inItem = true
                            currentItemTitle = ""
                            currentAudioUrl = ""
                            currentDuration = 0L
                        } else if (!inItem) {
                            if (tagName.equals("title", ignoreCase = true) && title.isBlank()) {
                                title = parser.nextText()
                            } else if (tagName.equals("itunes:author", ignoreCase = true) || tagName.equals("author", ignoreCase = true)) {
                                author = parser.nextText()
                            } else if (tagName.equals("description", ignoreCase = true) && description.isBlank()) {
                                description = parser.nextText()
                            } else if (tagName.equals("itunes:image", ignoreCase = true)) {
                                coverUrl = parser.getAttributeValue(null, "href") ?: ""
                            }
                        } else {
                            if (tagName.equals("title", ignoreCase = true)) {
                                currentItemTitle = parser.nextText()
                            } else if (tagName.equals("enclosure", ignoreCase = true)) {
                                currentAudioUrl = parser.getAttributeValue(null, "url") ?: ""
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (tagName.equals("item", ignoreCase = true)) {
                            inItem = false
                            if (currentAudioUrl.isNotBlank()) {
                                val podcastId = "pod_${feedUrl.hashCode()}"
                                episodes.add(
                                    PodcastEpisodeEntity(
                                        id = "ep_${currentAudioUrl.hashCode()}",
                                        podcastId = podcastId,
                                        title = currentItemTitle.ifBlank { "Episode ${episodes.size + 1}" },
                                        durationMs = 1800000L,
                                        audioUrl = currentAudioUrl,
                                        publishedAt = System.currentTimeMillis() - (episodes.size * 86400000L)
                                    )
                                )
                            }
                        }
                    }
                }
                eventType = parser.next()
            }

            val podcastId = "pod_${feedUrl.hashCode()}"
            val pod = PodcastEntity(
                id = podcastId,
                feedUrl = feedUrl,
                title = title.ifBlank { "Featured Podcast" },
                author = author.ifBlank { "UnMusic Network" },
                coverUrl = coverUrl.ifBlank { "https://images.unsplash.com/photo-1590602847861-f357a9332bbc?w=800&q=80" },
                description = description
            )
            database.podcastDao().insertPodcast(pod)
            database.podcastDao().insertEpisodes(episodes)
            true
        } catch (e: Exception) {
            false
        }
    }

    suspend fun getInitialPodcasts(): List<PodcastEntity> = withContext(Dispatchers.IO) {
        listOf(
            PodcastEntity(
                id = "pod_synth_talk",
                feedUrl = "https://example.com/synth.xml",
                title = "Electronic Soundscapes",
                author = "Synthesizer Society",
                coverUrl = "https://images.unsplash.com/photo-1590602847861-f357a9332bbc?w=800&q=80",
                description = "Exploring analog synthesizers, futuristic beats, and ambient architecture."
            ),
            PodcastEntity(
                id = "pod_deep_grooves",
                feedUrl = "https://example.com/grooves.xml",
                title = "Behind the Faders",
                author = "Audio Engineer Guild",
                coverUrl = "https://images.unsplash.com/photo-1478737270239-2f02b77fc618?w=800&q=80",
                description = "Deep dive into legendary record mixing sessions, acoustics, and master tapes."
            )
        )
    }
}
