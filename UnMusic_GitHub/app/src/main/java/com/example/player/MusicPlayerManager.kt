package com.example.player

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.os.CountDownTimer
import androidx.core.app.NotificationCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import coil.ImageLoader
import coil.request.ImageRequest
import com.example.MainActivity
import com.example.R
import com.example.core.data.db.AppDatabase
import com.example.core.data.model.TrackEntity
import com.example.innertube.InnerTubeClient
import com.example.innertube.NetResult
import com.example.lyrics.LyricsProvider
import com.example.personalization.RadioSelector
import com.example.personalization.TasteEngine
import com.example.lyrics.LyricsParser
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import java.io.File

enum class RepeatMode {
    OFF,
    ALL,
    ONE
}

private const val MAX_CONSECUTIVE_FAILURES = 3
private const val RADIO_MIN_AHEAD = 5
private const val RADIO_BATCH = 12

class MusicPlayerManager(
    private val context: Context,
    private val database: AppDatabase,
    private val innerTubeClient: InnerTubeClient,
    private val taste: TasteEngine? = null,
    private val lyricsProvider: LyricsProvider? = null
) {
    private val coroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    val exoPlayer: ExoPlayer = ExoPlayer.Builder(context)
        .setMediaSourceFactory(
            DefaultMediaSourceFactory(
                DefaultDataSource.Factory(
                    context,
                    DataSource.Factory {
                        ChunkedDataSource(
                            OkHttpDataSource.Factory(innerTubeClient.playbackHttpClient).createDataSource()
                        )
                    }
                )
            )
        )
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .setUsage(C.USAGE_MEDIA)
                .build(),
            true
        )
        .setHandleAudioBecomingNoisy(true)
        .build()
    val audioEffects = AudioEffectManager()

    // State flows for Compose UI
    private val _currentTrack = MutableStateFlow<TrackEntity?>(null)
    val currentTrack = _currentTrack.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying = _isPlaying.asStateFlow()

    private val _playbackPositionMs = MutableStateFlow(0L)
    val playbackPositionMs = _playbackPositionMs.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    val durationMs = _durationMs.asStateFlow()

    private val _queue = MutableStateFlow<List<TrackEntity>>(emptyList())
    val queue = _queue.asStateFlow()

    private val _currentIndex = MutableStateFlow(0)
    val currentIndex = _currentIndex.asStateFlow()

    private val _isShuffle = MutableStateFlow(false)
    val isShuffle = _isShuffle.asStateFlow()

    private val _repeatMode = MutableStateFlow(RepeatMode.OFF)
    val repeatMode = _repeatMode.asStateFlow()

    private val _activeLyricSubtext = MutableStateFlow("")
    val activeLyricSubtext = _activeLyricSubtext.asStateFlow()

    private val _sleepTimerRemainingMinutes = MutableStateFlow<Int?>(null)
    val sleepTimerRemainingMinutes = _sleepTimerRemainingMinutes.asStateFlow()

    private val _playerErrorMessage = MutableStateFlow<String?>(null)
    val playerErrorMessage = _playerErrorMessage.asStateFlow()

    fun clearPlayerErrorMessage() {
        _playerErrorMessage.value = null
    }

    private var sleepTimer: CountDownTimer? = null
    private var playJob: Job? = null
    private var consecutiveFailures = 0
    private var currentClient: String = ""
    private val triedClients = mutableSetOf<String>()
    private var recoveredCurrent = false
    private var countedPlayFor: String? = null
    private val errorNotes = StringBuilder()

    /** Putar otomatis: antrean diperpanjang dengan lagu yang nyambung (radio). */
    var autoRadio: Boolean = true
    private var radioContinuation: String? = null
    private var radioSeedId: String = ""
    private var radioJob: Job? = null
    private val radioIds = mutableSetOf<String>()
    private val recentIds = mutableListOf<String>()
    private var tasteRecorded = false

    /** Status pemuatan lirik lagu yang sedang diputar: IDLE, LOADING, FOUND, NONE. */
    private val _lyricsLoadState = MutableStateFlow("IDLE")
    val lyricsLoadState = _lyricsLoadState.asStateFlow()
    private var lyricsJob: Job? = null

    private val _playerErrorDetail = MutableStateFlow("")
    val playerErrorDetail = _playerErrorDetail.asStateFlow()
    private var positionUpdateJob: Job? = null
    private var lastNotifiedLyricLine = ""

    init {
        exoPlayer.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                _isPlaying.value = playing
                if (playing) {
                    consecutiveFailures = 0
                    val t = _currentTrack.value
                    if (t != null && countedPlayFor != t.id) {
                        countedPlayFor = t.id
                        coroutineScope.launch(Dispatchers.IO) { database.trackDao().incrementPlayCount(t.id) }
                    }
                    startPositionUpdater()
                } else {
                    stopPositionUpdater()
                }
                updateNotification()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_READY -> {
                        _durationMs.value = exoPlayer.duration.coerceAtLeast(0L)
                        audioEffects.attachAudioSession(exoPlayer.audioSessionId)
                        updateNotification()
                    }
                    Player.STATE_ENDED -> {
                        handleTrackEnded()
                    }
                    else -> {}
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                val track = _currentTrack.value ?: return
                android.util.Log.w("UnMusicPlayer", "Playback error ${error.errorCodeName} for ${track.title}: ${error.message}", error)
                val cause = error.cause
                val causeText = if (cause is androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) {
                    "HTTP ${cause.responseCode}"
                } else {
                    "${cause?.javaClass?.simpleName}: ${cause?.message}"
                }
                errorNotes.append("Pemutar via ").append(currentClient).append(": ")
                    .append(error.errorCodeName).append(" ").append(causeText).append('\n')
                coroutineScope.launch {
                    // Satu kali pemulihan: resolve ulang dengan klien lain, lanjut dari posisi terakhir.
                    if (!recoveredCurrent) {
                        recoveredCurrent = true
                        val wasLocal = currentClient == "LOCAL"
                        if (wasLocal) {
                            // File unduhan rusak: tandai belum diunduh agar bisa diunduh ulang
                            withContext(Dispatchers.IO) {
                                database.trackDao().updateDownloadStatus(track.id, false, "")
                            }
                            if (!isOnline()) {
                                errorNotes.append("File unduhan tidak dapat dibaca, unduh ulang saat online\n")
                                onTrackFailed(track, "file unduhan rusak")
                                return@launch
                            }
                        }
                        val resumeAt = if (wasLocal) 0L else exoPlayer.currentPosition.coerceAtLeast(0L)
                        val source = resolveSource(track, refresh = !wasLocal)
                        if (source != null) {
                            exoPlayer.setMediaItem(buildMediaItem(track, source), resumeAt)
                            exoPlayer.prepare()
                            exoPlayer.play()
                            return@launch
                        }
                    }
                    onTrackFailed(track, error.errorCodeName)
                }
            }
        })
    }

    private fun startPositionUpdater() {
        positionUpdateJob?.cancel()
        positionUpdateJob = coroutineScope.launch {
            while (isActive && exoPlayer.isPlaying) {
                val pos = exoPlayer.currentPosition
                _playbackPositionMs.value = pos

                delay(33) // ~30 fps updates for smooth seekbar and lyrics
            }
        }
    }

    private fun stopPositionUpdater() {
        positionUpdateJob?.cancel()
    }

    fun playTrack(track: TrackEntity, newQueue: List<TrackEntity>? = null) {
        ensureSession()
        recordListening(byEnd = false)
        playJob?.cancel()
        triedClients.clear()
        recoveredCurrent = false
        errorNotes.setLength(0)

        // Notifikasi dan panel kontrol sistem langsung memakai data lagu baru, tanpa menunggu stream selesai dicari.
        // Item sementara ini tidak dimuat (tanpa prepare), nanti diganti item asli dengan alamat audio.
        exoPlayer.stop()
        exoPlayer.setMediaItem(buildMediaItem(track, "unmusic://pending/${track.id}"))

        playJob = coroutineScope.launch {
            if (newQueue != null && newQueue.isNotEmpty()) {
                _queue.value = newQueue
                _currentIndex.value = newQueue.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
            } else if (!_queue.value.any { it.id == track.id }) {
                _queue.value = _queue.value + track
                _currentIndex.value = _queue.value.lastIndex
            } else {
                _currentIndex.value = _queue.value.indexOfFirst { it.id == track.id }
            }

            _currentTrack.value = track
            _playbackPositionMs.value = 0L
            tasteRecorded = false
            recentIds.remove(track.id)
            recentIds.add(track.id)
            while (recentIds.size > 40) recentIds.removeAt(0)
            maybeRefillRadio(track)
            loadLyrics(track)
            prefetchNextLyrics()

            // Dynamic dominant color extraction from cover bitmap (sampled 24x24)
            if (track.coverUrl.isNotBlank()) {
                coroutineScope.launch(Dispatchers.IO) {
                    try {
                        val loader = ImageLoader(context)
                        val req = ImageRequest.Builder(context)
                            .data(track.coverUrl)
                            .allowHardware(false)
                            .size(24, 24)
                            .build()
                        val result = loader.execute(req).drawable
                        val bmp = (result as? BitmapDrawable)?.bitmap
                        if (bmp != null) {
                            val extracted = extractDominantColor(bmp)
                            if (extracted != 0 && extracted != track.dominantColor) {
                                database.trackDao().updateColor(track.id, extracted)
                                _currentTrack.value = _currentTrack.value?.let {
                                    if (it.id == track.id) it.copy(dominantColor = extracted) else it
                                }
                            }
                        }
                    } catch (e: Exception) {
                        android.util.Log.w("UnMusicPlayer", "Dominant color extraction skipped: ${e.message}")
                    }
                }
            }

            // Resolve audio stream (file lokal dulu, lalu jaringan)
            val source = try {
                resolveSource(track, refresh = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("UnMusicPlayer", "Resolve gagal untuk ${track.title}: ${e.message}", e)
                null
            }

            if (source == null) {
                onTrackFailed(track, "stream tidak ditemukan")
                return@launch
            }

            exoPlayer.setMediaItem(buildMediaItem(track, source))
            exoPlayer.prepare()
            exoPlayer.play()
            updateNotification()
        }
    }

    private suspend fun resolveSource(track: TrackEntity, refresh: Boolean): String? {
        val fresh = withContext(Dispatchers.IO) { database.trackDao().getTrackById(track.id) }
        val localPath = when {
            fresh != null && fresh.isDownloaded && fresh.localFilePath.isNotBlank() -> fresh.localFilePath
            track.isDownloaded && track.localFilePath.isNotBlank() -> track.localFilePath
            else -> ""
        }
        if (localPath.isNotBlank() && File(localPath).exists()) {
            currentClient = "LOCAL"
            return android.net.Uri.fromFile(File(localPath)).toString()
        }
        if (!isOnline()) return null
        if (track.streamUrl.startsWith("http") && !track.streamUrl.contains("youtubei")) {
            currentClient = "DIRECT"
            return track.streamUrl
        }
        val resolved = innerTubeClient.resolveStream(
            videoId = track.id,
            excludeClients = if (refresh) triedClients.toSet() else emptySet(),
            forceRefresh = refresh
        ) ?: return null
        currentClient = resolved.client
        triedClients.add(resolved.client)
        return resolved.url
    }

    /**
     * Satu lagu gagal. Pindah ke lagu berikutnya hanya sampai batas kegagalan beruntun,
     * tanpa kembali ke awal antrean, supaya tidak terjadi loop skip tanpa akhir.
     */
    private fun isOnline(): Boolean {
        return try {
            val cm = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            caps != null && caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (e: Exception) {
            true
        }
    }

    /** Saat offline, lompat ke lagu berikutnya di antrean yang sudah diunduh. */
    private fun skipToNextDownloaded() {
        coroutineScope.launch {
            val q = _queue.value
            val start = _currentIndex.value
            var found: Int? = null
            for (step in 1 until q.size) {
                val i = (start + step) % q.size
                val t = withContext(Dispatchers.IO) { database.trackDao().getTrackById(q[i].id) }
                if (t != null && t.isDownloaded && t.localFilePath.isNotBlank() && File(t.localFilePath).exists()) {
                    found = i
                    break
                }
            }
            if (found != null) {
                _currentIndex.value = found
                playTrack(q[found])
            } else {
                exoPlayer.pause()
                _playerErrorMessage.value = "Tidak ada koneksi. Tidak ada lagu unduhan lain di antrean"
            }
        }
    }

    private fun onTrackFailed(track: TrackEntity, reason: String) {
        if (!isOnline()) {
            _playerErrorDetail.value = "${track.title}: tidak ada koneksi internet dan lagu belum diunduh"
            skipToNextDownloaded()
            return
        }
        consecutiveFailures++
        _playerErrorDetail.value = "${track.title} ($reason)\n$errorNotes${innerTubeClient.lastResolveReport}"
        android.util.Log.w("UnMusicPlayer", "Gagal memutar ${track.title}: $reason (beruntun=$consecutiveFailures)")

        if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
            consecutiveFailures = 0
            exoPlayer.pause()
            _playerErrorMessage.value = "Pemutaran dihentikan karena beberapa lagu gagal dimuat"
            return
        }

        val q = _queue.value
        val next = if (_isShuffle.value) {
            (q.indices - _currentIndex.value).randomOrNull()
        } else {
            (_currentIndex.value + 1).takeIf { it in q.indices }
        }
        if (next == null) {
            exoPlayer.pause()
            _playerErrorMessage.value = "Lagu tidak dapat diputar"
            return
        }
        _playerErrorMessage.value = "Lagu tidak dapat diputar"
        _currentIndex.value = next
        playTrack(q[next])
    }

    /** Coba lagi lagu saat ini dari awal (dipakai setelah pemutaran dihentikan). */
    fun retryCurrent() {
        _currentTrack.value?.let { playTrack(it) }
    }

    fun togglePlayPause() {
        if (exoPlayer.isPlaying) {
            exoPlayer.pause()
        } else {
            if (exoPlayer.playbackState == Player.STATE_IDLE && _currentTrack.value != null) {
                _currentTrack.value?.let { playTrack(it) }
            } else {
                exoPlayer.play()
            }
        }
    }

    fun skipToNext() {
        val q = _queue.value
        if (q.isEmpty()) return
        if (!isOnline()) {
            skipToNextDownloaded()
            return
        }
        val nextIdx = if (_isShuffle.value) {
            (q.indices - _currentIndex.value).randomOrNull() ?: 0
        } else {
            (_currentIndex.value + 1) % q.size
        }
        _currentIndex.value = nextIdx
        playTrack(q[nextIdx])
    }

    fun skipToPrevious() {
        val q = _queue.value
        if (q.isEmpty()) return
        if (exoPlayer.currentPosition > 3000L) {
            seekTo(0L)
            return
        }
        val prevIdx = if (_currentIndex.value - 1 < 0) q.size - 1 else _currentIndex.value - 1
        _currentIndex.value = prevIdx
        playTrack(q[prevIdx])
    }

    private fun handleTrackEnded() {
        recordListening(byEnd = true)
        when (_repeatMode.value) {
            RepeatMode.ONE -> {
                tasteRecorded = false
                seekTo(0L)
                exoPlayer.play()
            }
            RepeatMode.ALL -> skipToNext()
            RepeatMode.OFF -> {
                if (_currentIndex.value < _queue.value.size - 1) {
                    skipToNext()
                } else if (autoRadio) {
                    _currentTrack.value?.let { maybeRefillRadio(it) }
                    coroutineScope.launch {
                        withTimeoutOrNull(10_000L) { radioJob?.join() }
                        if (_currentIndex.value < _queue.value.size - 1) {
                            skipToNext()
                        } else {
                            exoPlayer.pause()
                            seekTo(0L)
                        }
                    }
                } else {
                    exoPlayer.pause()
                    seekTo(0L)
                }
            }
        }
    }

    /** Muat lirik dari database; bila belum ada, cari dari sumber online lalu simpan untuk offline. */
    private fun loadLyrics(track: TrackEntity, force: Boolean = false) {
        lyricsJob?.cancel()
        lyricsJob = coroutineScope.launch {
            try {
                if (force) {
                    // Cari ulang: buang lirik lama yang mungkin salah
                    withContext(Dispatchers.IO) { database.trackDao().updateLyrics(track.id, "", "") }
                    applyLyrics(track.id, "", "")
                } else {
                    val stored = withContext(Dispatchers.IO) { database.trackDao().getTrackById(track.id) }
                    val knownLrc = (stored?.lyricsLrc?.takeIf { it.isNotBlank() } ?: track.lyricsLrc)
                        .takeUnless { com.example.lyrics.lyricsLookLikeHtml(it) }.orEmpty()
                    if (knownLrc.isNotBlank()) {
                        applyLyrics(track.id, knownLrc, stored?.lyricsSyncedJson ?: track.lyricsSyncedJson)
                        _lyricsLoadState.value = "FOUND"
                        return@launch
                    }
                }
                val provider = lyricsProvider
                if (provider == null || !isOnline()) {
                    _lyricsLoadState.value = "NONE"
                    return@launch
                }
                _lyricsLoadState.value = "LOADING"
                val result = provider.fetch(track.title, track.artist, com.example.lyrics.lyricDurationSec(track.durationMs), track.id, track.album)
                if (result == null) {
                    _lyricsLoadState.value = "NONE"
                    return@launch
                }
                withContext(Dispatchers.IO) {
                    database.trackDao().insertTracksIgnore(listOf(track))
                    database.trackDao().repairArtist(track.id, track.artist, track.album)
                    database.trackDao().updateLyrics(track.id, result.lrc, result.syncedJson)
                }
                applyLyrics(track.id, result.lrc, result.syncedJson)
                _lyricsLoadState.value = "FOUND"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("UnMusicLyrics", "Lirik gagal dimuat: ${e.message}")
                _lyricsLoadState.value = "NONE"
            }
        }
    }

    private val attemptedLyricPrefetch = mutableSetOf<String>()

    /** Mengambil lirik 2 lagu berikutnya di antrean lebih awal, supaya langsung tersedia saat gilirannya. */
    private fun prefetchNextLyrics() {
        val provider = lyricsProvider ?: return
        if (!isOnline()) return
        val q = _queue.value
        val idx = _currentIndex.value
        val last = minOf(idx + 3, q.size)
        if (idx + 1 >= last) return
        for (i in (idx + 1) until last) {
            val t = q[i]
            if (!attemptedLyricPrefetch.add(t.id)) continue
            coroutineScope.launch {
                try {
                    val stored = withContext(Dispatchers.IO) { database.trackDao().getTrackById(t.id) }
                    if (stored == null || stored.lyricsLrc.isBlank()) {
                        val r = provider.fetch(t.title, t.artist, com.example.lyrics.lyricDurationSec(t.durationMs), t.id, t.album)
                        if (r != null) {
                            withContext(Dispatchers.IO) {
                                database.trackDao().insertTracksIgnore(listOf(t))
                                database.trackDao().updateLyrics(t.id, r.lrc, r.syncedJson)
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    android.util.Log.w("UnMusicLyrics", "Prefetch lirik gagal: ${e.message}")
                }
            }
        }
    }

    private fun applyLyrics(trackId: String, lrc: String, syncedJson: String) {
        val current = _currentTrack.value
        if (current != null && current.id == trackId) {
            _currentTrack.value = current.copy(lyricsLrc = lrc, lyricsSyncedJson = syncedJson)
        }
    }

    fun retryLyrics() {
        _currentTrack.value?.let { loadLyrics(it, force = true) }
    }

    private fun recordListening(byEnd: Boolean) {
        val t = _currentTrack.value ?: return
        if (tasteRecorded) return
        val dur = _durationMs.value
        val pos = if (byEnd) dur else _playbackPositionMs.value
        if (!byEnd && pos < 3_000L) return
        tasteRecorded = true
        val completed = byEnd || (dur > 0 && pos >= dur * 8 / 10)
        val skippedEarly = !byEnd && pos < 20_000L
        taste?.record(t, pos, completed, skippedEarly)
    }

    /**
     * Radio: bila sisa antrean menipis, ambil lagu lanjutan dari lagu yang sedang diputar.
     * Hasil disaring menurut bahasa, mood, dan selera pengguna. Bila jaringan gagal,
     * antrean diisi dari pustaka lokal.
     */
    private fun maybeRefillRadio(current: TrackEntity) {
        if (!autoRadio) return
        val remaining = _queue.value.size - 1 - _currentIndex.value
        if (remaining >= RADIO_MIN_AHEAD) return
        if (radioJob?.isActive == true) return

        radioJob = coroutineScope.launch {
            try {
                // Lagu dari file unduhan atau tanpa internet: lanjutkan hanya dengan lagu unduhan, tanpa radio online
                val fresh = withContext(Dispatchers.IO) { database.trackDao().getTrackById(current.id) }
                val isLocal = fresh != null && fresh.isDownloaded && fresh.localFilePath.isNotBlank() &&
                    File(fresh.localFilePath).exists()
                val offlineMode = isLocal || !isOnline()

                var added = 0
                if (!offlineMode) {
                    if (current.id !in radioIds || radioContinuation == null) {
                        radioSeedId = current.id
                        radioContinuation = null
                    }
                    var attempts = 0
                    while (added < 6 && attempts < 3) {
                        attempts++
                        val res = withContext(Dispatchers.IO) {
                            innerTubeClient.getRadio(radioSeedId, radioContinuation)
                        }
                        val page = (res as? NetResult.Success)?.data ?: break
                        radioContinuation = page.continuation
                        val exclude = _queue.value.map { it.id }.toSet() + recentIds
                        val picked = withContext(Dispatchers.Default) {
                            RadioSelector.select(current, page.tracks, exclude, taste, RADIO_BATCH)
                        }
                        if (picked.isNotEmpty()) {
                            picked.forEach { radioIds.add(it.id) }
                            _queue.value = _queue.value + picked
                            added += picked.size
                            prefetchNextLyrics()
                            coroutineScope.launch(Dispatchers.IO) {
                                database.trackDao().insertTracksIgnore(picked)
                            }
                        }
                        if (page.continuation == null) break
                    }
                }

                if (added == 0) {
                    val local = withContext(Dispatchers.IO) { database.trackDao().getAllTracksFlow().first() }
                    val downloaded = local.filter {
                        it.isDownloaded && it.localFilePath.isNotBlank() && File(it.localFilePath).exists()
                    }
                    val pool = if (offlineMode) downloaded else local
                    val exclude = _queue.value.map { it.id }.toSet() + recentIds
                    val picked = withContext(Dispatchers.Default) {
                        RadioSelector.select(current, pool.shuffled(), exclude, taste, RADIO_BATCH)
                    }
                    if (picked.isNotEmpty()) {
                        picked.forEach { radioIds.add(it.id) }
                        _queue.value = _queue.value + picked
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("UnMusicRadio", "Radio gagal: ${e.message}", e)
            }
        }
    }


    fun seekTo(positionMs: Long) {
        _playbackPositionMs.value = positionMs
        exoPlayer.seekTo(positionMs)
    }

    fun toggleShuffle() {
        _isShuffle.value = !_isShuffle.value
    }

    fun toggleRepeat() {
        _repeatMode.value = when (_repeatMode.value) {
            RepeatMode.OFF -> RepeatMode.ALL
            RepeatMode.ALL -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.OFF
        }
    }

    fun toggleLikeCurrentTrack() {
        val track = _currentTrack.value ?: return
        val newLiked = !track.isLiked
        val updated = track.copy(isLiked = newLiked)
        _currentTrack.value = updated
        _queue.value = _queue.value.map { if (it.id == track.id) updated else it }
        coroutineScope.launch(Dispatchers.IO) {
            database.trackDao().updateLiked(track.id, newLiked)
            (context.applicationContext as? com.example.UnMusicApplication)?.cloudChanged()
        }
        if (newLiked) taste?.record(track, 0L, completed = false, skippedEarly = false, liked = true)
        updateNotification()
    }

    fun setQueue(list: List<TrackEntity>, startIndex: Int = 0) {
        _queue.value = list
        if (startIndex in list.indices) {
            _currentIndex.value = startIndex
            playTrack(list[startIndex])
        }
    }

    fun addToQueue(track: TrackEntity) {
        _queue.value = _queue.value + track
    }

    fun playNext(track: TrackEntity) {
        val currentQ = _queue.value.toMutableList()
        val insertAt = (_currentIndex.value + 1).coerceAtMost(currentQ.size)
        currentQ.add(insertAt, track)
        _queue.value = currentQ
    }

    /** Menghapus semua lagu setelah lagu yang sedang diputar. */
    fun clearUpcoming() {
        val q = _queue.value
        val keep = (_currentIndex.value + 1).coerceIn(0, q.size)
        _queue.value = q.take(keep)
    }

    fun removeFromQueue(index: Int) {
        if (index in _queue.value.indices) {
            val list = _queue.value.toMutableList()
            list.removeAt(index)
            _queue.value = list
            if (index < _currentIndex.value) {
                _currentIndex.value--
            }
        }
    }

    fun setSleepTimer(minutes: Int) {
        sleepTimer?.cancel()
        if (minutes <= 0) {
            _sleepTimerRemainingMinutes.value = null
            return
        }
        _sleepTimerRemainingMinutes.value = minutes
        val totalMs = minutes * 60 * 1000L
        sleepTimer = object : CountDownTimer(totalMs, 60 * 1000L) {
            override fun onTick(millisUntilFinished: Long) {
                _sleepTimerRemainingMinutes.value = (millisUntilFinished / (60 * 1000L)).toInt() + 1
            }

            override fun onFinish() {
                _sleepTimerRemainingMinutes.value = null
                exoPlayer.pause()
            }
        }.start()
    }

    private val _playbackSpeed = MutableStateFlow(1f)
    val playbackSpeed = _playbackSpeed.asStateFlow()

    fun setPlaybackSpeed(speed: Float) {
        exoPlayer.setPlaybackSpeed(speed)
        _playbackSpeed.value = speed
    }

    /** Notifikasi dan kontrol layar kunci dikelola PlayerService lewat MediaSession. */
    private fun updateNotification() {
    }

    private var controllerFuture: com.google.common.util.concurrent.ListenableFuture<androidx.media3.session.MediaController>? = null

    /** Menghubungkan ke PlayerService sehingga kontrol media sistem tampil saat lagu diputar. */
    private fun ensureSession() {
        if (controllerFuture != null) return
        try {
            val token = androidx.media3.session.SessionToken(
                context,
                android.content.ComponentName(context, PlayerService::class.java)
            )
            controllerFuture = androidx.media3.session.MediaController.Builder(context, token).buildAsync()
        } catch (e: Exception) {
            android.util.Log.w("UnMusicPlayer", "Sesi media gagal dibuat: ${e.message}")
        }
    }

    private fun buildMediaItem(track: TrackEntity, source: String): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(track.artist)
            .setAlbumTitle(track.album.ifBlank { null })
            .setArtworkUri(if (track.coverUrl.isNotBlank()) android.net.Uri.parse(track.coverUrl) else null)
            .build()
        return MediaItem.Builder()
            .setUri(source)
            .setMediaId(track.id)
            .setMediaMetadata(metadata)
            .build()
    }

    private fun extractDominantColor(bitmap: Bitmap): Int {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) return 0xFF27272A.toInt()

        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        var bestColor = 0
        var maxScore = -1f

        val hsv = FloatArray(3)
        for (pixel in pixels) {
            val alpha = (pixel shr 24) and 0xFF
            if (alpha < 128) continue
            android.graphics.Color.colorToHSV(pixel, hsv)
            val sat = hsv[1]
            val value = hsv[2]
            if (sat > 0.25f && value in 0.15f..0.95f) {
                val score = sat * (1f - kotlin.math.abs(value - 0.5f))
                if (score > maxScore) {
                    maxScore = score
                    bestColor = pixel
                }
            }
        }

        if (maxScore > 0f) {
            return bestColor or (0xFF shl 24)
        }

        var totalR = 0L
        var totalG = 0L
        var totalB = 0L
        var count = 0
        for (pixel in pixels) {
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            totalR += r
            totalG += g
            totalB += b
            count++
        }
        if (count > 0) {
            val avgR = (totalR / count).toInt()
            val avgG = (totalG / count).toInt()
            val avgB = (totalB / count).toInt()
            return (0xFF shl 24) or (avgR shl 16) or (avgG shl 8) or avgB
        }
        return 0xFF27272A.toInt()
    }

    fun release() {
        stopPositionUpdater()
        sleepTimer?.cancel()
        audioEffects.release()
        exoPlayer.release()
    }
}
