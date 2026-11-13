package com.example.core.data.model

import androidx.compose.runtime.Immutable
import androidx.room.Entity
import androidx.room.PrimaryKey

@Immutable
@Entity(tableName = "tracks")
data class TrackEntity(
    @PrimaryKey val id: String,
    val title: String,
    val artist: String,
    val album: String = "",
    val durationMs: Long = 0L,
    val coverUrl: String = "",
    val streamUrl: String = "",
    val isLiked: Boolean = false,
    val playCount: Int = 0,
    val lastPlayedAt: Long = 0L,
    val isDownloaded: Boolean = false,
    val localFilePath: String = "",
    val lyricsLrc: String = "",
    val lyricsSyncedJson: String = "",
    val dominantColor: Int = 0xFF27272A.toInt(),
    val genre: String = "Pop",
    val videoId: String = "",
    val unplayableUntil: Long = 0L,
    val language: String = "",
    val mood: String = "",
    val skipCount: Int = 0
)

@Immutable
@Entity(tableName = "artists")
data class ArtistEntity(
    @PrimaryKey val id: String,
    val name: String,
    val bannerUrl: String = "",
    val songCount: Int = 0
)

enum class PlaylistCoverType {
    DEFAULT,
    CUSTOM_IMAGE,
    MOSAIC,
    ANIMATED_GRADIENT
}

@Immutable
@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey val id: String,
    val name: String,
    val coverType: PlaylistCoverType = PlaylistCoverType.DEFAULT,
    val customCoverUri: String = "",
    val isSmart: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "playlist_tracks", primaryKeys = ["playlistId", "trackId"])
data class PlaylistTrackCrossRef(
    val playlistId: String,
    val trackId: String,
    val orderIndex: Int = 0
)

@Immutable
@Entity(tableName = "podcasts")
data class PodcastEntity(
    @PrimaryKey val id: String,
    val feedUrl: String,
    val title: String,
    val author: String,
    val coverUrl: String,
    val description: String
)

@Immutable
@Entity(tableName = "podcast_episodes")
data class PodcastEpisodeEntity(
    @PrimaryKey val id: String,
    val podcastId: String,
    val title: String,
    val durationMs: Long = 0L,
    val audioUrl: String,
    val playbackPositionMs: Long = 0L,
    val isPlayed: Boolean = false,
    val publishedAt: Long = 0L
)

@Entity(tableName = "search_history")
data class SearchHistoryEntity(
    @PrimaryKey val query: String,
    val timestamp: Long = System.currentTimeMillis()
)

@Immutable
@Entity(tableName = "play_events")
data class PlayEventEntity(
    @PrimaryKey(autoGenerate = true) val eventId: Long = 0L,
    val trackId: String,
    val title: String,
    val artist: String,
    val language: String = "",
    val mood: String = "",
    val genre: String = "Pop",
    val startedAt: Long = System.currentTimeMillis(),
    val hourOfDay: Int = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY),
    val dayOfWeek: Int = java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_WEEK),
    val playedMs: Long = 0L,
    val completed: Boolean = false,
    val skippedEarly: Boolean = false,
    val liked: Boolean = false,
    val source: String = "home"
)

@Immutable
@Entity(tableName = "playback_logs")
data class PlaybackLogEntity(
    @PrimaryKey(autoGenerate = true) val logId: Long = 0L,
    val videoId: String,
    val trackTitle: String,
    val clientName: String,
    val httpStatus: Int,
    val playabilityStatus: String,
    val reason: String,
    val exoPlayerError: String = "",
    val timestamp: Long = System.currentTimeMillis()
)

@Immutable
@Entity(tableName = "taste_preferences")
data class TastePreferenceEntity(
    @PrimaryKey val key: String,
    val weight: Float,
    val category: String, // "artist", "genre", "language", "mood"
    val updatedAt: Long = System.currentTimeMillis()
)
