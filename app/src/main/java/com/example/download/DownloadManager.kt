package com.example.download

import android.content.Context
import android.util.Log
import com.example.core.data.db.AppDatabase
import com.example.core.data.model.TrackEntity
import com.example.innertube.InnerTubeClient
import com.example.lyrics.LyricsProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.ConcurrentHashMap

enum class DownloadStatus { QUEUED, DOWNLOADING, DONE, FAILED }

data class DownloadProgress(
    val trackId: String,
    val progress: Float,
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val error: String = ""
) {
    val isDownloading: Boolean get() = status == DownloadStatus.DOWNLOADING || status == DownloadStatus.QUEUED
    val isFailed: Boolean get() = status == DownloadStatus.FAILED
}

/**
 * Unduhan berjalan di scope sendiri (tidak ikut mati saat layar ditutup), maksimal 2 sekaligus.
 * Audio dan lirik disimpan di perangkat sehingga bisa diputar penuh tanpa internet.
 */
class DownloadManager(
    private val context: Context,
    private val database: AppDatabase,
    private val innerTubeClient: InnerTubeClient,
    private val lyricsProvider: LyricsProvider
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gate = Semaphore(2)
    private val jobs = ConcurrentHashMap<String, Job>()

    private val _downloadStates = MutableStateFlow<Map<String, DownloadProgress>>(emptyMap())
    val downloadStates = _downloadStates.asStateFlow()

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val events: SharedFlow<String> = _events.asSharedFlow()

    private val downloadsDir: File
        get() = File(context.filesDir, "downloads").apply { if (!exists()) mkdirs() }

    /** Masukkan lagu ke antrean unduhan. Aman dipanggil berulang. */
    fun enqueue(track: TrackEntity) {
        val current = _downloadStates.value[track.id]
        if (current != null && current.isDownloading) return
        setState(track.id, 0f, DownloadStatus.QUEUED)
        jobs[track.id] = scope.launch {
            gate.withPermit { runDownload(track) }
        }
    }

    fun cancel(trackId: String) {
        jobs.remove(trackId)?.cancel()
        File(downloadsDir, "$trackId.part").delete()
        removeState(trackId)
    }

    private suspend fun runDownload(track: TrackEntity) {
        try {
            setState(track.id, 0.02f, DownloadStatus.DOWNLOADING)
            database.trackDao().insertTracksIgnore(listOf(track))
            database.trackDao().repairArtist(track.id, track.artist, track.album)

            // 1. Lirik dicari bersamaan dengan audio dan disimpan agar tersedia saat offline
            scope.launch {
                try {
                    val stored = database.trackDao().getTrackById(track.id)
                    if (stored == null || stored.lyricsLrc.isBlank()) {
                        val lyrics = lyricsProvider.fetch(track.title, track.artist, com.example.lyrics.lyricDurationSec(track.durationMs), track.id, track.album)
                        if (lyrics != null) {
                            database.trackDao().updateLyrics(track.id, lyrics.lrc, lyrics.syncedJson)
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Lirik gagal disimpan: ${e.message}")
                }
            }
            setState(track.id, 0.05f, DownloadStatus.DOWNLOADING)

            // 2. Audio, dicoba hingga 3 kali dengan klien berbeda
            val excluded = mutableSetOf<String>()
            var lastError = "stream tidak ditemukan"
            var result: File? = null
            for (attempt in 0 until 3) {
                if (result != null) break
                val resolved = innerTubeClient.resolveStream(track.id, excluded, forceRefresh = attempt > 0)
                if (resolved == null) {
                    lastError = innerTubeClient.lastResolveReport.lines().lastOrNull { it.isNotBlank() } ?: lastError
                    break
                }
                excluded.add(resolved.client)
                try {
                    result = downloadFile(track, resolved.url, resolved.mimeType)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    lastError = "${resolved.client}: ${e.message}"
                    Log.w(TAG, "Unduhan ${track.title} gagal via ${resolved.client}: ${e.message}")
                    File(downloadsDir, "${track.id}.part").delete()
                }
            }

            val file = result
            if (file == null) {
                fail(track, lastError)
                return
            }

            database.trackDao().updateDownloadStatus(track.id, downloaded = true, path = file.absolutePath)
            setState(track.id, 1f, DownloadStatus.DONE)
            _events.tryEmit("Siap offline: ${track.title}")
        } catch (e: CancellationException) {
            File(downloadsDir, "${track.id}.part").delete()
            removeState(track.id)
            throw e
        } catch (e: Exception) {
            fail(track, e.message ?: e.javaClass.simpleName)
        } finally {
            jobs.remove(track.id)
        }
    }

    private suspend fun downloadFile(track: TrackEntity, url: String, mime: String): File {
        val ext = when {
            mime.contains("mp4") -> "m4a"
            mime.contains("webm") -> "webm"
            else -> "audio"
        }
        val part = File(downloadsDir, "${track.id}.part")
        val target = File(downloadsDir, "${track.id}.$ext")
        if (part.exists()) part.delete()
        val http = innerTubeClient.playbackHttpClient

        val total = url.toHttpUrlOrNull()?.queryParameter("clen")?.toLongOrNull() ?: 0L
        var written = 0L

        if (total > 0L) {
            // Beberapa potongan diunduh bersamaan (range berbatas) lalu ditulis ke posisi masing-masing
            RandomAccessFile(part, "rw").use { raf ->
                raf.setLength(total)
                val ranges = ArrayList<LongArray>()
                var start = 0L
                while (start < total) {
                    val end = minOf(start + CHUNK, total) - 1
                    ranges.add(longArrayOf(start, end))
                    start = end + 1
                }
                val done = AtomicLong(0L)
                val limiter = Semaphore(PARALLEL)
                coroutineScope {
                    ranges.map { range ->
                        async {
                            limiter.withPermit {
                                currentCoroutineContext().ensureActive()
                                val bytes = fetchRange(http, url, range[0], range[1])
                                synchronized(raf) {
                                    raf.seek(range[0])
                                    raf.write(bytes)
                                }
                                val soFar = done.addAndGet(bytes.size.toLong())
                                setState(
                                    track.id,
                                    0.05f + 0.93f * (soFar.toFloat() / total).coerceIn(0f, 1f),
                                    DownloadStatus.DOWNLOADING
                                )
                            }
                        }
                    }.awaitAll()
                }
                written = done.get()
            }
        } else {
            FileOutputStream(part).use { out ->
                val req = Request.Builder().url(url).build()
                http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                    val body = resp.body ?: throw IOException("respons kosong")
                    val length = body.contentLength()
                    val buffer = ByteArray(32 * 1024)
                    body.byteStream().use { input ->
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buffer)
                            if (n < 0) break
                            out.write(buffer, 0, n)
                            written += n
                            if (length > 0) {
                                setState(
                                    track.id,
                                    0.05f + 0.93f * (written.toFloat() / length).coerceIn(0f, 1f),
                                    DownloadStatus.DOWNLOADING
                                )
                            }
                        }
                    }
                }
            }
        }

        if (total > 0L && part.length() != total) {
            throw IOException("ukuran tidak sesuai (${part.length()} dari $total)")
        }
        if (part.length() < 10_000L) throw IOException("file terlalu kecil")

        if (target.exists()) target.delete()
        if (!part.renameTo(target)) {
            part.copyTo(target, overwrite = true)
            part.delete()
        }
        return target
    }

    /** Mengambil satu potongan byte; diulang hingga 3 kali bila gagal. */
    private fun fetchRange(http: OkHttpClient, url: String, start: Long, end: Long): ByteArray {
        var lastError: IOException? = null
        for (attempt in 0 until 3) {
            try {
                val req = Request.Builder().url(url).header("Range", "bytes=$start-$end").build()
                http.newCall(req).execute().use { resp ->
                    if (resp.code != 206) throw IOException("HTTP ${resp.code}")
                    val bytes = resp.body?.bytes() ?: throw IOException("respons kosong")
                    val expected = (end - start + 1).toInt()
                    if (bytes.size < expected) throw IOException("potongan tidak lengkap")
                    return if (bytes.size == expected) bytes else bytes.copyOf(expected)
                }
            } catch (e: IOException) {
                lastError = e
            }
        }
        throw lastError ?: IOException("potongan gagal")
    }

    private fun fail(track: TrackEntity, reason: String) {
        val map = _downloadStates.value.toMutableMap()
        map[track.id] = DownloadProgress(track.id, 0f, DownloadStatus.FAILED, reason)
        _downloadStates.value = map
        _events.tryEmit("Unduhan gagal: ${track.title}")
        Log.w(TAG, "Unduhan gagal ${track.title}: $reason")
    }

    @Synchronized
    private fun setState(trackId: String, progress: Float, status: DownloadStatus) {
        val map = _downloadStates.value.toMutableMap()
        map[trackId] = DownloadProgress(trackId, progress, status)
        _downloadStates.value = map
    }

    @Synchronized
    private fun removeState(trackId: String) {
        val map = _downloadStates.value.toMutableMap()
        map.remove(trackId)
        _downloadStates.value = map
    }

    suspend fun deleteDownload(track: TrackEntity) {
        val stored = database.trackDao().getTrackById(track.id)
        val path = stored?.localFilePath?.ifBlank { track.localFilePath } ?: track.localFilePath
        if (path.isNotBlank()) {
            val file = File(path)
            if (file.exists()) file.delete()
        }
        database.trackDao().updateDownloadStatus(track.id, downloaded = false, path = "")
        removeState(track.id)
    }

    fun getTotalDownloadedSizeBytes(): Long {
        return downloadsDir.listFiles()?.sumOf { it.length() } ?: 0L
    }

    fun clearAllDownloads() {
        downloadsDir.listFiles()?.forEach { it.delete() }
        _downloadStates.value = emptyMap()
        scope.launch { database.trackDao().clearAllDownloadFlags() }
    }

    private companion object {
        const val TAG = "UnMusicDownload"
        const val CHUNK = 512L * 1024L
        const val PARALLEL = 4
    }
}
