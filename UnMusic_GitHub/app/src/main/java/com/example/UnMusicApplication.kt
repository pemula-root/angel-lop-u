package com.example

import com.example.cloud.CloudRepository
import android.app.Application
import android.util.Log
import com.example.core.data.db.AppDatabase
import com.example.core.data.repository.PreferencesRepository
import com.example.download.DownloadManager
import com.example.innertube.InnerTubeClient
import com.example.innertube.NetResult
import com.example.lyrics.LyricsProvider
import com.example.mix.AutoMixGenerator
import com.example.personalization.RecommendationRefresher
import com.example.personalization.TasteEngine
import com.example.player.MusicPlayerManager
import com.example.podcast.PodcastManager
import com.example.stats.StatsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class UnMusicApplication : Application() {
    private val applicationScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    lateinit var database: AppDatabase
        private set
    lateinit var preferencesRepository: PreferencesRepository
        private set
    lateinit var innerTubeClient: InnerTubeClient
        private set
    lateinit var lyricsProvider: LyricsProvider
        private set
    lateinit var tasteEngine: TasteEngine
        private set
    lateinit var playerManager: MusicPlayerManager
        private set
    lateinit var downloadManager: DownloadManager
        private set
    lateinit var podcastManager: PodcastManager
        private set
    lateinit var autoMixGenerator: AutoMixGenerator
        private set
    lateinit var statsManager: StatsManager
        private set
    lateinit var cloud: CloudRepository
        private set

    /** Dipanggil setelah perubahan lokal (suka, playlist) agar dikirim ke cloud setelah jeda singkat. */
    fun cloudChanged() {
        if (::cloud.isInitialized) cloud.notifyChanged()
    }

    /** Dipanggil saat aplikasi masuk latar belakang. */
    fun cloudPushSoon() {
        if (::cloud.isInitialized) cloud.pushSoon()
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        database = AppDatabase.getInstance(this)
        preferencesRepository = PreferencesRepository(this)
        innerTubeClient = InnerTubeClient()
        lyricsProvider = LyricsProvider()
        lyricsProvider.ytMusicLyrics = { videoId -> innerTubeClient.getYtMusicLyrics(videoId) }
        tasteEngine = TasteEngine(this)
        playerManager = MusicPlayerManager(this, database, innerTubeClient, tasteEngine, lyricsProvider)
        downloadManager = DownloadManager(this, database, innerTubeClient, lyricsProvider)
        podcastManager = PodcastManager(database)
        autoMixGenerator = AutoMixGenerator(database)
        statsManager = StatsManager(database)
        cloud = CloudRepository(this, database)
        cloud.init()

        // Lirik hasil pencocokan lama bisa salah lagu: dibersihkan sekali, lalu dicari ulang saat diputar
        val migrations = getSharedPreferences("migrations", MODE_PRIVATE)
        if (!migrations.getBoolean("lyrics_v5", false)) {
            applicationScope.launch {
                try {
                    database.trackDao().clearAllLyrics()
                    migrations.edit().putBoolean("lyrics_v3", true).apply()
                } catch (e: Exception) {
                    Log.w("UnMusicApp", "Gagal membersihkan lirik lama: ${e.message}")
                }
            }
        }

        val refresher = RecommendationRefresher(this, database, innerTubeClient, tasteEngine)
        applicationScope.launch {
            delay(6000L)
            try {
                refresher.seedCatalogIfNeeded()
                refresher.refreshIfNeeded()
            } catch (e: Exception) {
                Log.w("UnMusicApp", "Rekomendasi gagal diperbarui: ${e.message}")
            }
        }

        // Seed or refresh home charts in background without blocking UI
        applicationScope.launch {
            try {
                // Delete any legacy static mock tracks
                val localTracks = database.trackDao().searchLocalTracks("")
                localTracks.filter { it.id.startsWith("track_") }.forEach {
                    database.trackDao().deleteTrack(it)
                }

                when (val result = innerTubeClient.getHomeCharts()) {
                    is NetResult.Success -> {
                        database.trackDao().insertTracks(result.data)
                        Log.i("UnMusicApp", "Cached ${result.data.size} live YouTube Music tracks into Room")
                    }
                    is NetResult.Error -> {
                        Log.w("UnMusicApp", "Failed to fetch home charts: ${result.message} code: ${result.httpCode}")
                    }
                    is NetResult.Empty -> {
                        Log.w("UnMusicApp", "Empty home charts response: ${result.reason}")
                    }
                }
            } catch (e: Exception) {
                Log.e("UnMusicApp", "Background sync exception", e)
            }
        }
    }

    companion object {
        lateinit var instance: UnMusicApplication
            private set
    }
}
