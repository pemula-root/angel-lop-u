package com.example.stats

import com.example.core.data.dao.ArtistPlayStat
import com.example.core.data.db.AppDatabase
import com.example.core.data.model.TrackEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.Locale

data class YearRewindData(
    val totalMinutesListened: Long,
    val topTrack: TrackEntity?,
    val topArtists: List<ArtistPlayStat>,
    val topGenre: String,
    val mostActiveDay: String
)

/** Statistik dihitung dari riwayat pemutaran nyata di perangkat. Pengguna baru melihat nilai kosong. */
class StatsManager(private val database: AppDatabase) {

    suspend fun getRewindData(): YearRewindData = withContext(Dispatchers.IO) {
        val played = database.trackDao().searchLocalTracks("").filter { it.playCount > 0 }
        if (played.isEmpty()) {
            return@withContext YearRewindData(0L, null, emptyList(), "-", "-")
        }

        val totalMs = database.trackDao().getTotalListeningDurationMs() ?: 0L
        val topTrack = played.maxByOrNull { it.playCount }
        val topArtists = database.trackDao().getTopArtists()
        val topGenre = played.groupBy { it.genre }.maxByOrNull { group -> group.value.sumOf { it.playCount } }?.key ?: "-"

        val dayNames = listOf("Minggu", "Senin", "Selasa", "Rabu", "Kamis", "Jumat", "Sabtu")
        val activeDay = played.filter { it.lastPlayedAt > 0L }
            .groupBy { track ->
                Calendar.getInstance(Locale.getDefault()).apply { timeInMillis = track.lastPlayedAt }
                    .get(Calendar.DAY_OF_WEEK)
            }
            .maxByOrNull { group -> group.value.sumOf { it.playCount } }
            ?.key

        YearRewindData(
            totalMinutesListened = totalMs / (1000L * 60L),
            topTrack = topTrack,
            topArtists = topArtists,
            topGenre = topGenre,
            mostActiveDay = if (activeDay != null) dayNames[activeDay - 1] else "-"
        )
    }
}
