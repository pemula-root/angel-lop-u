package com.example.core.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.core.data.dao.PlaylistDao
import com.example.core.data.dao.PodcastDao
import com.example.core.data.dao.SearchHistoryDao
import com.example.core.data.dao.TrackDao
import com.example.core.data.model.*

@Database(
    entities = [
        TrackEntity::class,
        ArtistEntity::class,
        PlaylistEntity::class,
        PlaylistTrackCrossRef::class,
        PodcastEntity::class,
        PodcastEpisodeEntity::class,
        SearchHistoryEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun trackDao(): TrackDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun podcastDao(): PodcastDao
    abstract fun searchHistoryDao(): SearchHistoryDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "unmusic.db"
                )
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
