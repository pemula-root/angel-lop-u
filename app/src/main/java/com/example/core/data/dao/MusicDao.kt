package com.example.core.data.dao

import androidx.room.*
import com.example.core.data.model.*
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackDao {
    @Query("SELECT * FROM tracks ORDER BY lastPlayedAt DESC")
    fun getAllTracksFlow(): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE id = :id")
    suspend fun getTrackById(id: String): TrackEntity?

    @Query("SELECT * FROM tracks WHERE id = :id")
    fun getTrackFlowById(id: String): Flow<TrackEntity?>

    @Query("SELECT * FROM tracks WHERE isLiked = 1 ORDER BY lastPlayedAt DESC")
    fun getLikedTracksFlow(): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE isDownloaded = 1 ORDER BY title ASC")
    fun getDownloadedTracksFlow(): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE lastPlayedAt > 0 ORDER BY lastPlayedAt DESC LIMIT :limit")
    fun getRecentlyPlayedFlow(limit: Int = 20): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks ORDER BY playCount DESC LIMIT :limit")
    fun getMostPlayedFlow(limit: Int = 20): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE artist = :artist ORDER BY playCount DESC")
    fun getTracksByArtistFlow(artist: String): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE genre = :genre ORDER BY playCount DESC")
    fun getTracksByGenreFlow(genre: String): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE title LIKE '%' || :query || '%' OR artist LIKE '%' || :query || '%' OR lyricsLrc LIKE '%' || :query || '%'")
    suspend fun searchLocalTracks(query: String): List<TrackEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateTrack(track: TrackEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTracks(tracks: List<TrackEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTracksIgnore(tracks: List<TrackEntity>)

    @Query("UPDATE tracks SET isLiked = :isLiked WHERE id = :id")
    suspend fun updateLiked(id: String, isLiked: Boolean)

    @Query("UPDATE tracks SET playCount = playCount + 1, lastPlayedAt = :timestamp WHERE id = :id")
    suspend fun incrementPlayCount(id: String, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE tracks SET lyricsLrc = :lrc, lyricsSyncedJson = :syncedJson WHERE id = :id")
    suspend fun updateLyrics(id: String, lrc: String, syncedJson: String)

    @Query("SELECT * FROM tracks WHERE isLiked = 1 ORDER BY lastPlayedAt DESC")
    suspend fun getLikedTracksOnce(): List<TrackEntity>

    @Query("SELECT * FROM tracks WHERE lastPlayedAt > 0 ORDER BY lastPlayedAt DESC LIMIT :limit")
    suspend fun getRecentlyPlayedOnce(limit: Int): List<TrackEntity>

    @Query("UPDATE tracks SET isLiked = 0")
    suspend fun clearAllLikes()

    @Query("UPDATE tracks SET lastPlayedAt = :ts WHERE id = :id AND lastPlayedAt = 0")
    suspend fun setLastPlayedIfNever(id: String, ts: Long)

    @Query("UPDATE tracks SET lyricsLrc = '', lyricsSyncedJson = ''")
    suspend fun clearAllLyrics()

    @Query("UPDATE tracks SET artist = :artist, album = :album WHERE id = :id AND artist IN ('Song', 'Lagu', 'Video', 'Artist', 'Single', 'EP', 'Album', 'Music', 'Musik')")
    suspend fun repairArtist(id: String, artist: String, album: String)

    @Query("UPDATE tracks SET isDownloaded = 0, localFilePath = ''")
    suspend fun clearAllDownloadFlags()

    @Query("UPDATE tracks SET isDownloaded = :downloaded, localFilePath = :path WHERE id = :id")
    suspend fun updateDownloadStatus(id: String, downloaded: Boolean, path: String)

    @Query("UPDATE tracks SET dominantColor = :color WHERE id = :id")
    suspend fun updateColor(id: String, color: Int)

    @Delete
    suspend fun deleteTrack(track: TrackEntity)

    @Query("SELECT SUM(durationMs * playCount) FROM tracks WHERE playCount > 0")
    suspend fun getTotalListeningDurationMs(): Long?

    @Query("SELECT artist, SUM(playCount) as totalPlays FROM tracks WHERE playCount > 0 GROUP BY artist HAVING SUM(playCount) > 0 ORDER BY totalPlays DESC LIMIT 5")
    suspend fun getTopArtists(): List<ArtistPlayStat>
}

data class ArtistPlayStat(
    val artist: String,
    val totalPlays: Int
)

@Dao
interface PlaylistDao {
    @Query("SELECT * FROM playlists ORDER BY createdAt DESC")
    fun getAllPlaylistsFlow(): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM playlists WHERE id = :id")
    suspend fun getPlaylistById(id: String): PlaylistEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylist(playlist: PlaylistEntity)

    @Delete
    suspend fun deletePlaylist(playlist: PlaylistEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylistTrackCrossRef(crossRef: PlaylistTrackCrossRef)

    @Query("DELETE FROM playlist_tracks WHERE playlistId = :playlistId AND trackId = :trackId")
    suspend fun removeTrackFromPlaylist(playlistId: String, trackId: String)

    @Query("""
        SELECT t.* FROM tracks t 
        INNER JOIN playlist_tracks pt ON t.id = pt.trackId 
        WHERE pt.playlistId = :playlistId 
        ORDER BY pt.orderIndex ASC
    """)
    fun getTracksForPlaylistFlow(playlistId: String): Flow<List<TrackEntity>>

    @Query("SELECT COUNT(*) FROM playlist_tracks WHERE playlistId = :playlistId")
    suspend fun getTrackCountForPlaylist(playlistId: String): Int

    @Query("SELECT * FROM playlists WHERE isSmart = 0 ORDER BY createdAt DESC")
    suspend fun getCustomPlaylistsOnce(): List<PlaylistEntity>

    @Query("""
        SELECT t.* FROM tracks t 
        INNER JOIN playlist_tracks pt ON t.id = pt.trackId 
        WHERE pt.playlistId = :playlistId 
        ORDER BY pt.orderIndex ASC
    """)
    suspend fun getTracksForPlaylistOnce(playlistId: String): List<TrackEntity>

    @Query("DELETE FROM playlist_tracks WHERE playlistId = :playlistId")
    suspend fun clearPlaylistTracks(playlistId: String)

    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun deletePlaylistById(id: String)
}

@Dao
interface PodcastDao {
    @Query("SELECT * FROM podcasts ORDER BY title ASC")
    fun getAllPodcastsFlow(): Flow<List<PodcastEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPodcast(podcast: PodcastEntity)

    @Query("SELECT * FROM podcast_episodes WHERE podcastId = :podcastId ORDER BY publishedAt DESC")
    fun getEpisodesForPodcast(podcastId: String): Flow<List<PodcastEpisodeEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEpisodes(episodes: List<PodcastEpisodeEntity>)

    @Query("UPDATE podcast_episodes SET playbackPositionMs = :position, isPlayed = :isPlayed WHERE id = :id")
    suspend fun updateEpisodeProgress(id: String, position: Long, isPlayed: Boolean)
}

@Dao
interface SearchHistoryDao {
    @Query("SELECT * FROM search_history ORDER BY timestamp DESC LIMIT 20")
    fun getRecentSearchesFlow(): Flow<List<SearchHistoryEntity>>

    @Query("SELECT * FROM search_history ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentOnce(limit: Int): List<SearchHistoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSearch(search: SearchHistoryEntity)

    @Query("DELETE FROM search_history WHERE query = :query")
    suspend fun deleteSearch(query: String)

    @Query("DELETE FROM search_history")
    suspend fun clearHistory()
}
