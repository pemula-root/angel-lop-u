package com.example.mix

import com.example.core.data.db.AppDatabase
import com.example.core.data.model.TrackEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class AutoMix(
    val id: String,
    val title: String,
    val subtitle: String,
    val coverGradientStart: Long,
    val coverGradientEnd: Long,
    val tracks: List<TrackEntity>
)

class AutoMixGenerator(private val database: AppDatabase) {

    suspend fun generateMixes(): List<AutoMix> = withContext(Dispatchers.IO) {
        val allTracks = database.trackDao().searchLocalTracks("")
        if (allTracks.isEmpty()) return@withContext emptyList()

        val dailyMix = AutoMix(
            id = "mix_daily",
            title = "Daily Mix",
            subtitle = "Curated based on your recent favorites",
            coverGradientStart = 0xFF6366F1,
            coverGradientEnd = 0xFFA855F7,
            tracks = allTracks.shuffled().take(6)
        )

        val chillMix = AutoMix(
            id = "mix_chill",
            title = "Chill & Ambient Mix",
            subtitle = "Mellow frequencies to unwind",
            coverGradientStart = 0xFF0EA5E9,
            coverGradientEnd = 0xFF14B8A6,
            tracks = allTracks.filter { it.genre in listOf("Ambient", "Folk", "R&B") }.ifEmpty { allTracks.take(4) }
        )

        val energyMix = AutoMix(
            id = "mix_energy",
            title = "High Velocity Mix",
            subtitle = "Upbeat drives and workouts",
            coverGradientStart = 0xFFF43F5E,
            coverGradientEnd = 0xFFF97316,
            tracks = allTracks.filter { it.genre in listOf("Electronic", "Synthwave", "Pop") }.ifEmpty { allTracks.take(4) }
        )

        listOf(dailyMix, chillMix, energyMix)
    }
}
