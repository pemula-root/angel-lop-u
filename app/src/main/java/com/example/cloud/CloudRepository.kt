package com.example.cloud

import android.app.Activity
import android.content.Context
import android.util.Log
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.example.core.data.db.AppDatabase
import com.example.core.data.model.PlaylistEntity
import com.example.core.data.model.PlaylistTrackCrossRef
import com.example.core.data.model.SearchHistoryEntity
import com.example.core.data.model.TrackEntity
import com.google.android.gms.tasks.Task
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.UserProfileChangeRequest
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class CloudUser(
    val uid: String,
    val name: String,
    val email: String,
    val photoUrl: String,
    val isGuest: Boolean
)

data class SyncStatus(
    val syncing: Boolean = false,
    val lastSyncMs: Long = 0L,
    val message: String = "",
    val isError: Boolean = false,
    val favorites: Int = 0,
    val playlists: Int = 0
)

private data class CloudTrack(
    val videoId: String,
    val title: String,
    val artist: String,
    val album: String,
    val thumbnail: String,
    val durationSec: Long
)

private data class CloudPlaylist(
    val id: String,
    val name: String,
    val songs: List<CloudTrack>,
    val createdAt: Long,
    val updatedAt: Long
)

private data class CloudData(
    val favorites: List<CloudTrack>,
    val recent: List<CloudTrack>,
    val playlists: List<CloudPlaylist>,
    val searches: List<String>
)

private suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
}

/**
 * Akun dan sinkronisasi cloud lewat Firebase. Data disimpan di dokumen `users/{uid}` dengan susunan yang sama
 * dengan versi web UnMusic (favorites, recentlyPlayed, playlists, recentSearches), sehingga satu akun
 * dipakai bersama di web dan aplikasi. Bila aplikasi dihapus lalu dipasang lagi, masuk ke akun yang sama
 * mengembalikan playlist dan lagu yang disukai.
 */
class CloudRepository(
    private val context: Context,
    private val database: AppDatabase
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs = context.getSharedPreferences("cloud", Context.MODE_PRIVATE)

    private val clientId: String = prefs.getString("client_id", null) ?: ("android-" + UUID.randomUUID()).also {
        prefs.edit().putString("client_id", it).apply()
    }

    private val _available = MutableStateFlow(false)
    val available: StateFlow<Boolean> = _available.asStateFlow()

    private val _user = MutableStateFlow<CloudUser?>(null)
    val user: StateFlow<CloudUser?> = _user.asStateFlow()

    private val _status = MutableStateFlow(SyncStatus(lastSyncMs = prefs.getLong("last_sync", 0L)))
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    private var listener: ListenerRegistration? = null
    private var pushJob: Job? = null
    private var syncing = false

    // ------------------------------------------------------------------ inisialisasi

    fun init() {
        if (!ensureFirebase()) return
        _available.value = true
        FirebaseAuth.getInstance().addAuthStateListener { auth -> onAuthChanged(auth.currentUser) }
    }

    private fun ensureFirebase(): Boolean {
        return try {
            if (FirebaseApp.getApps(context).isEmpty()) {
                val options = FirebaseOptions.Builder()
                    .setApiKey(CloudConfig.API_KEY)
                    .setApplicationId(CloudConfig.APP_ID)
                    .setProjectId(CloudConfig.PROJECT_ID)
                    .setGcmSenderId(CloudConfig.SENDER_ID)
                    .setStorageBucket(CloudConfig.STORAGE_BUCKET)
                    .build()
                FirebaseApp.initializeApp(context, options)
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "Firebase tidak dapat diinisialisasi: ${e.message}")
            false
        }
    }

    private fun FirebaseUser.toCloudUser() = CloudUser(
        uid = uid,
        name = displayName.orEmpty(),
        email = email.orEmpty(),
        photoUrl = photoUrl?.toString().orEmpty(),
        isGuest = isAnonymous
    )

    private fun onAuthChanged(user: FirebaseUser?) {
        _user.value = user?.toCloudUser()
        listener?.remove()
        listener = null
        if (user != null) {
            scope.launch {
                mergeSync(user.uid)
                startListening(user.uid)
            }
        }
    }

    private fun userDoc(uid: String): DocumentReference =
        FirebaseFirestore.getInstance().collection("users").document(uid)

    // ------------------------------------------------------------------ masuk dan keluar

    suspend fun signInEmail(email: String, password: String) {
        FirebaseAuth.getInstance().signInWithEmailAndPassword(email.trim(), password).awaitResult()
    }

    suspend fun signUpEmail(name: String, email: String, password: String) {
        val result = FirebaseAuth.getInstance().createUserWithEmailAndPassword(email.trim(), password).awaitResult()
        val user = result.user
        if (user != null && name.isNotBlank()) {
            user.updateProfile(UserProfileChangeRequest.Builder().setDisplayName(name.trim()).build()).awaitResult()
            _user.value = user.toCloudUser()
        }
    }

    suspend fun signInGuest() {
        FirebaseAuth.getInstance().signInAnonymously().awaitResult()
    }

    suspend fun sendPasswordReset(email: String) {
        FirebaseAuth.getInstance().sendPasswordResetEmail(email.trim()).awaitResult()
    }

    private fun webClientId(): String {
        val resId = context.resources.getIdentifier("default_web_client_id", "string", context.packageName)
        return if (resId != 0) context.getString(resId) else CloudConfig.WEB_CLIENT_ID
    }

    fun googleSignInReady(): Boolean = webClientId().isNotBlank()

    suspend fun signInGoogle(activity: Activity) {
        val serverClientId = webClientId()
        if (serverClientId.isBlank()) {
            throw IllegalStateException("GOOGLE_NOT_CONFIGURED")
        }
        val option = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(false)
            .setServerClientId(serverClientId)
            .setAutoSelectEnabled(false)
            .build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        val result = CredentialManager.create(activity).getCredential(activity, request)
        val credential = result.credential
        if (credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            val google = GoogleIdTokenCredential.createFrom(credential.data)
            val firebaseCredential = GoogleAuthProvider.getCredential(google.idToken, null)
            FirebaseAuth.getInstance().signInWithCredential(firebaseCredential).awaitResult()
        } else {
            throw IllegalStateException("Kredensial Google tidak dikenali")
        }
    }

    fun signOut() {
        // Data terakhir didorong dulu supaya tidak hilang, lalu keluar
        val current = _user.value
        scope.launch {
            if (current != null) {
                try { pushNow(current.uid) } catch (_: Exception) { }
            }
            FirebaseAuth.getInstance().signOut()
        }
    }

    /** Pesan galat berbahasa Indonesia untuk ditampilkan ke pengguna. */
    fun errorMessage(e: Throwable): String {
        if (e is GetCredentialCancellationException) return "Masuk dengan Google dibatalkan."
        if (e is NoCredentialException) return "Tidak ada akun Google di perangkat ini."
        if (e is GetCredentialException) return "Masuk dengan Google gagal. Pastikan SHA-1 sudah didaftarkan di Firebase."
        if (e is FirebaseNetworkException) return "Tidak ada koneksi internet."
        if (e is IllegalStateException && e.message == "GOOGLE_NOT_CONFIGURED") {
            return "Google belum disiapkan. Tambahkan google-services.json (lihat FIREBASE_SETUP.md)."
        }
        if (e is FirebaseAuthException) {
            return when (e.errorCode) {
                "ERROR_INVALID_EMAIL" -> "Format email tidak valid."
                "ERROR_WRONG_PASSWORD", "ERROR_INVALID_CREDENTIAL", "ERROR_USER_NOT_FOUND" -> "Email atau kata sandi salah."
                "ERROR_EMAIL_ALREADY_IN_USE" -> "Email ini sudah terdaftar. Silakan masuk."
                "ERROR_WEAK_PASSWORD" -> "Kata sandi terlalu lemah, minimal 6 karakter."
                "ERROR_TOO_MANY_REQUESTS" -> "Terlalu banyak percobaan. Coba lagi beberapa saat."
                "ERROR_OPERATION_NOT_ALLOWED" -> "Metode masuk ini belum diaktifkan di Firebase."
                "ERROR_USER_DISABLED" -> "Akun ini dinonaktifkan."
                else -> e.message ?: "Terjadi kesalahan saat masuk."
            }
        }
        return e.message ?: "Terjadi kesalahan."
    }

    // ------------------------------------------------------------------ sinkronisasi

    /** Sinkron manual: gabungkan data cloud dan lokal, lalu kirim hasilnya. */
    suspend fun syncNow() {
        val uid = _user.value?.uid ?: return
        mergeSync(uid)
    }

    /** Dijadwalkan setelah perubahan lokal (suka, playlist). Dikirim setelah jeda singkat. */
    fun notifyChanged() {
        val uid = _user.value?.uid ?: return
        pushJob?.cancel()
        pushJob = scope.launch {
            delay(3000L)
            try { pushNow(uid) } catch (e: Exception) { Log.w(TAG, "push gagal: ${e.message}") }
        }
    }

    /** Dipanggil saat aplikasi masuk latar belakang agar perubahan terakhir tersimpan. */
    fun pushSoon() {
        val uid = _user.value?.uid ?: return
        scope.launch {
            try { pushNow(uid) } catch (e: Exception) { Log.w(TAG, "push gagal: ${e.message}") }
        }
    }

    private suspend fun mergeSync(uid: String) {
        if (syncing) return
        syncing = true
        _status.value = _status.value.copy(syncing = true, message = "Menyinkronkan", isError = false)
        try {
            val snap = userDoc(uid).get().awaitResult()
            val remote = if (snap.exists()) parseRemote(snap.data) else null
            val local = buildLocal()
            val merged = merge(local, remote)
            applyToLocal(merged, replace = false)
            pushNow(uid)
        } catch (e: Exception) {
            Log.w(TAG, "sinkronisasi gagal: ${e.message}")
            _status.value = _status.value.copy(syncing = false, message = errorMessage(e), isError = true)
        } finally {
            syncing = false
        }
    }

    private fun startListening(uid: String) {
        listener?.remove()
        listener = userDoc(uid).addSnapshotListener { snap, error ->
            if (error != null || snap == null || !snap.exists()) return@addSnapshotListener
            if (snap.metadata.hasPendingWrites()) return@addSnapshotListener
            val from = snap.getString("client")
            if (from == clientId || syncing) return@addSnapshotListener
            val data = parseRemote(snap.data) ?: return@addSnapshotListener
            scope.launch {
                try {
                    // Perubahan dari perangkat lain (web atau HP lain) dianggap acuan terbaru
                    applyToLocal(data, replace = true)
                    markSynced(data)
                } catch (e: Exception) {
                    Log.w(TAG, "gagal menerapkan data cloud: ${e.message}")
                }
            }
        }
    }

    private suspend fun pushNow(uid: String) {
        val local = buildLocal()
        val payload = hashMapOf<String, Any?>(
            "favorites" to local.favorites.map { it.toMap() },
            "recentlyPlayed" to local.recent.map { it.toMap() },
            "playlists" to local.playlists.map {
                mapOf(
                    "id" to it.id,
                    "name" to it.name,
                    "songs" to it.songs.map { s -> s.toMap() },
                    "createdAt" to it.createdAt,
                    "updatedAt" to it.updatedAt
                )
            },
            "recentSearches" to local.searches,
            "client" to clientId,
            "platform" to "android",
            "updatedAt" to FieldValue.serverTimestamp()
        )
        userDoc(uid).set(payload, SetOptions.merge()).awaitResult()
        markSynced(local)
    }

    private fun markSynced(data: CloudData) {
        val now = System.currentTimeMillis()
        prefs.edit().putLong("last_sync", now).apply()
        _status.value = SyncStatus(
            syncing = false,
            lastSyncMs = now,
            message = "Tersinkron",
            isError = false,
            favorites = data.favorites.size,
            playlists = data.playlists.size
        )
    }

    // ------------------------------------------------------------------ data lokal <-> cloud

    private suspend fun buildLocal(): CloudData {
        val trackDao = database.trackDao()
        val playlistDao = database.playlistDao()
        val favorites = trackDao.getLikedTracksOnce().take(1500).map { it.toCloud() }
        val recent = trackDao.getRecentlyPlayedOnce(50).map { it.toCloud() }
        val playlists = playlistDao.getCustomPlaylistsOnce().map { p ->
            CloudPlaylist(
                id = p.id,
                name = p.name,
                songs = playlistDao.getTracksForPlaylistOnce(p.id).map { it.toCloud() },
                createdAt = p.createdAt,
                updatedAt = System.currentTimeMillis()
            )
        }
        val searches = database.searchHistoryDao().getRecentOnce(12).map { it.query }
        return CloudData(favorites, recent, playlists, searches)
    }

    private fun merge(local: CloudData, remote: CloudData?): CloudData {
        if (remote == null) return local
        fun union(a: List<CloudTrack>, b: List<CloudTrack>, cap: Int) =
            (a + b).distinctBy { it.videoId }.take(cap)

        val playlistsById = LinkedHashMap<String, CloudPlaylist>()
        for (p in remote.playlists) playlistsById[p.id] = p
        for (p in local.playlists) {
            val existing = playlistsById[p.id]
            playlistsById[p.id] = if (existing == null) p else existing.copy(
                songs = union(existing.songs, p.songs, 5000)
            )
        }
        return CloudData(
            favorites = union(remote.favorites, local.favorites, 1500),
            recent = union(local.recent, remote.recent, 50),
            playlists = playlistsById.values.toList(),
            searches = (local.searches + remote.searches).distinct().take(12)
        )
    }

    private suspend fun applyToLocal(data: CloudData, replace: Boolean) {
        val trackDao = database.trackDao()
        val playlistDao = database.playlistDao()

        // Lagu disukai
        trackDao.insertTracksIgnore(data.favorites.map { it.toEntity() })
        if (replace) trackDao.clearAllLikes()
        for (t in data.favorites) trackDao.updateLiked(t.videoId, true)

        // Riwayat putar
        trackDao.insertTracksIgnore(data.recent.map { it.toEntity() })
        val now = System.currentTimeMillis()
        data.recent.forEachIndexed { i, t -> trackDao.setLastPlayedIfNever(t.videoId, now - i * 1000L) }

        // Playlist
        val remoteIds = data.playlists.map { it.id }.toSet()
        for (cp in data.playlists) {
            val existing = playlistDao.getPlaylistById(cp.id)
            val entity = (existing ?: PlaylistEntity(id = cp.id, name = cp.name, createdAt = cp.createdAt))
                .copy(name = cp.name)
            playlistDao.insertPlaylist(entity)
            trackDao.insertTracksIgnore(cp.songs.map { it.toEntity() })
            playlistDao.clearPlaylistTracks(cp.id)
            cp.songs.forEachIndexed { index, s ->
                playlistDao.insertPlaylistTrackCrossRef(PlaylistTrackCrossRef(cp.id, s.videoId, index))
            }
        }
        if (replace) {
            for (p in playlistDao.getCustomPlaylistsOnce()) {
                if (p.id !in remoteIds) {
                    playlistDao.clearPlaylistTracks(p.id)
                    playlistDao.deletePlaylistById(p.id)
                }
            }
        }

        // Riwayat pencarian
        val searchDao = database.searchHistoryDao()
        data.searches.forEachIndexed { i, q ->
            searchDao.insertSearch(SearchHistoryEntity(query = q, timestamp = now - i * 1000L))
        }
    }

    // ------------------------------------------------------------------ konversi

    private fun TrackEntity.toCloud() = CloudTrack(
        videoId = id,
        title = title,
        artist = artist,
        album = album,
        thumbnail = coverUrl,
        durationSec = durationMs / 1000L
    )

    private fun CloudTrack.toEntity() = TrackEntity(
        id = videoId,
        title = title,
        artist = artist.ifBlank { "Artist" },
        album = album,
        durationMs = durationSec * 1000L,
        coverUrl = thumbnail.ifBlank { "https://i.ytimg.com/vi/$videoId/hqdefault.jpg" },
        streamUrl = "",
        videoId = videoId,
        genre = "Music",
        dominantColor = 0xFF27272A.toInt()
    )

    /** Dituliskan dengan beberapa nama kolom agar terbaca oleh versi web maupun aplikasi. */
    private fun CloudTrack.toMap(): Map<String, Any?> = mapOf(
        "videoId" to videoId,
        "id" to videoId,
        "title" to title,
        "artist" to artist,
        "uploaderName" to artist,
        "album" to album,
        "thumbnail" to thumbnail,
        "thumbnailUrl" to thumbnail,
        "duration" to durationSec
    )

    private fun parseTrack(m: Map<*, *>): CloudTrack? {
        val id = (m["videoId"] ?: m["id"])?.toString()?.trim().orEmpty()
        if (id.isBlank()) return null
        val title = (m["title"] ?: m["name"])?.toString().orEmpty()
        val artist = (m["artist"] ?: m["uploaderName"] ?: m["author"] ?: m["channel"])?.toString().orEmpty()
        val thumb = (m["thumbnail"] ?: m["thumbnailUrl"] ?: m["cover"] ?: m["image"])?.toString().orEmpty()
        val duration = (m["duration"] as? Number)?.toLong() ?: 0L
        return CloudTrack(id, title, artist, m["album"]?.toString().orEmpty(), thumb, duration)
    }

    private fun parseTracks(value: Any?): List<CloudTrack> =
        (value as? List<*>)?.mapNotNull { (it as? Map<*, *>)?.let { m -> parseTrack(m) } }.orEmpty()

    private fun parseRemote(map: Map<String, Any?>?): CloudData? {
        if (map == null) return null
        val playlists = (map["playlists"] as? List<*>)?.mapNotNull { raw ->
            val m = raw as? Map<*, *> ?: return@mapNotNull null
            val id = m["id"]?.toString().orEmpty()
            if (id.isBlank()) return@mapNotNull null
            CloudPlaylist(
                id = id,
                name = m["name"]?.toString().orEmpty().ifBlank { "Playlist" },
                songs = parseTracks(m["songs"] ?: m["tracks"]),
                createdAt = (m["createdAt"] as? Number)?.toLong() ?: System.currentTimeMillis(),
                updatedAt = (m["updatedAt"] as? Number)?.toLong() ?: 0L
            )
        }.orEmpty()
        val searches = (map["recentSearches"] as? List<*>)?.mapNotNull { it?.toString() }.orEmpty()
        return CloudData(
            favorites = parseTracks(map["favorites"]),
            recent = parseTracks(map["recentlyPlayed"]),
            playlists = playlists,
            searches = searches
        )
    }

    private companion object {
        const val TAG = "UnMusicCloud"
    }
}
