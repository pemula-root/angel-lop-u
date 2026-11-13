package com.example.personalization

import android.content.Context
import com.example.core.data.db.AppDatabase
import com.example.innertube.InnerTubeClient
import com.example.innertube.NetResult
import kotlinx.coroutines.flow.first

/**
 * Menambah stok lagu untuk Home berdasarkan selera: dari beberapa lagu favorit diambil radio-nya,
 * disaring bahasa/mood, lalu disimpan ke database. Pengguna baru (belum ada riwayat) dilewati.
 */
class RecommendationRefresher(
    context: Context,
    private val database: AppDatabase,
    private val client: InnerTubeClient,
    private val taste: TasteEngine
) {
    private val prefs = context.getSharedPreferences("reco", Context.MODE_PRIVATE)

    /**
     * Mengisi katalog dengan banyak lagu: pencarian populer sesuai bahasa perangkat dan
     * artis yang sering diputar. Hasilnya diurutkan di Home oleh profil selera.
     */
    suspend fun seedCatalogIfNeeded() {
        val now = System.currentTimeMillis()
        if (now - prefs.getLong("catalog", 0L) < 12 * 3_600_000L) return

        val language = taste.dominantLanguage()
        val base = if (language == "id") {
            listOf(
                "lagu indonesia terbaru", "top hits indonesia", "lagu pop indonesia",
                "lagu galau indonesia", "lagu santai indonesia", "lagu semangat indonesia",
                "lagu barat populer", "lagu viral"
            )
        } else {
            listOf("top hits", "new music", "pop hits", "chill songs", "workout songs", "sad songs", "viral songs")
        }
        val queries = base + taste.topArtists(5)

        var inserted = 0
        for (q in queries) {
            val res = client.search(q)
            val songs = (res as? NetResult.Success)?.data?.songs ?: continue
            if (songs.isNotEmpty()) {
                database.trackDao().insertTracksIgnore(songs.take(25))
                inserted += songs.size
            }
        }
        if (inserted > 0) prefs.edit().putLong("catalog", now).apply()
    }

    suspend fun refreshIfNeeded(force: Boolean = false) {
        if (!taste.enabled) return
        val now = System.currentTimeMillis()
        if (!force && now - prefs.getLong("last", 0L) < 4 * 3_600_000L) return

        val all = database.trackDao().getAllTracksFlow().first()
        val seeds = all.filter { it.playCount > 0 || it.isLiked }
            .sortedByDescending { taste.score(it, now) }
            .take(6)
        if (seeds.isEmpty()) return

        val known = all.map { it.id }.toMutableSet()
        for (seed in seeds) {
            val res = client.getRadio(seed.id)
            val page = (res as? NetResult.Success)?.data ?: continue
            val picked = RadioSelector.select(seed, page.tracks, known, taste, 24)
            if (picked.isNotEmpty()) {
                database.trackDao().insertTracksIgnore(picked)
                picked.forEach { known.add(it.id) }
            }
        }
        prefs.edit().putLong("last", now).apply()
    }
}
