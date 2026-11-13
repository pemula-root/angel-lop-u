package com.example.innertube

import android.util.Log
import com.example.core.data.model.TrackEntity
import com.example.innertube.parser.ArtistPageData
import com.example.innertube.parser.ArtistParser
import com.example.innertube.parser.BrowseParser
import com.example.innertube.parser.SearchParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class SearchResultGroup(
    val topResultArtist: InnerTubeArtist? = null,
    val songs: List<TrackEntity> = emptyList(),
    val albums: List<InnerTubeAlbum> = emptyList(),
    val artists: List<InnerTubeArtist> = emptyList(),
    val playlists: List<InnerTubePlaylist> = emptyList()
)

data class InnerTubeArtist(
    val id: String,
    val name: String,
    val bannerUrl: String,
    val subscribers: String = ""
)

data class InnerTubeAlbum(
    val id: String,
    val title: String,
    val artist: String,
    val year: String,
    val coverUrl: String
)

data class InnerTubePlaylist(
    val id: String,
    val title: String,
    val author: String,
    val trackCount: String,
    val coverUrl: String
)

data class NetworkDebugEntry(
    val timestamp: Long,
    val endpoint: String,
    val isSuccess: Boolean,
    val code: Int?,
    val message: String
)

class InnerTubeClient {
    private val connectionPool = ConnectionPool(8, 5, TimeUnit.MINUTES)
    private val client = OkHttpClient.Builder()
        .connectionPool(connectionPool)
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val streamCache = ConcurrentHashMap<String, ResolvedStream>() // videoId -> stream
    private var visitorData: String = ""

    val debugLogs = Collections.synchronizedList(mutableListOf<NetworkDebugEntry>())

    companion object {
        private const val TAG = "UnMusicNet"
        private const val YTM_API_BASE = "https://music.youtube.com/youtubei/v1"
        private const val WEB_REMIX_VERSION = "1.20241028.01.00"
        private const val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        // Filter params for server-side search
        const val FILTER_SONGS = "EgWKAQIIAWoKEAkQBRAKEAMQBBAF"
        const val FILTER_ARTISTS = "EgWKAQIIAWoKEAkQChAKEAMQBBAF"
        const val FILTER_ALBUMS = "EgWKAQIIAWoKEAkQChAKEAMQBBAF"
        const val FILTER_PLAYLISTS = "EgWKAQIIAWoKEAkQBRAKEAMQBBAF"
    }

    private fun logDebug(endpoint: String, isSuccess: Boolean, code: Int?, message: String) {
        val entry = NetworkDebugEntry(System.currentTimeMillis(), endpoint, isSuccess, code, message)
        debugLogs.add(0, entry)
        if (debugLogs.size > 50) debugLogs.removeAt(debugLogs.size - 1)
    }

    private fun buildYtMusicContext(): JSONObject {
        val locale = Locale.getDefault()
        val lang = if (locale.language.isNotBlank()) locale.language else "en"
        val country = if (locale.country.isNotBlank()) locale.country else "US"
        val tz = TimeZone.getDefault()
        val offsetMinutes = tz.rawOffset / (1000 * 60)

        return JSONObject().apply {
            put("client", JSONObject().apply {
                put("clientName", "WEB_REMIX")
                put("clientVersion", WEB_REMIX_VERSION)
                put("hl", lang)
                put("gl", country)
                put("timeZone", tz.id)
                put("utcOffsetMinutes", offsetMinutes)
                put("userAgent", DESKTOP_UA)
                if (visitorData.isNotBlank()) {
                    put("visitorData", visitorData)
                }
            })
        }
    }

    private fun buildHeaders(): Headers {
        val locale = Locale.getDefault()
        val lang = if (locale.language.isNotBlank()) locale.language else "en"
        val builder = Headers.Builder()
            .add("Content-Type", "application/json")
            .add("Origin", "https://music.youtube.com")
            .add("Referer", "https://music.youtube.com/")
            .add("User-Agent", DESKTOP_UA)
            .add("Accept-Language", "$lang;q=0.9,en;q=0.8")

        if (visitorData.isNotBlank()) {
            builder.add("X-Goog-Visitor-Id", visitorData)
        }
        return builder.build()
    }

    /**
     * Executes safe network request with automatic retry (up to 2 times) and comprehensive logging.
     */
    private suspend fun safeCall(url: String, bodyJson: JSONObject): NetResult<JSONObject> = withContext(Dispatchers.IO) {
        var attempt = 0
        val maxAttempts = 3
        var lastException: Throwable? = null

        while (attempt < maxAttempts) {
            attempt++
            try {
                val request = Request.Builder()
                    .url(url)
                    .post(bodyJson.toString().toRequestBody(JSON_MEDIA_TYPE))
                    .headers(buildHeaders())
                    .build()

                val response = client.newCall(request).execute()
                val code = response.code
                val rawBody = response.body?.string() ?: ""

                if (response.isSuccessful) {
                    val json = JSONObject(rawBody)

                    // Extract and cache visitorData if returned
                    val returnedVisitor = json.optJSONObject("responseContext")?.optString("visitorData")
                    if (!returnedVisitor.isNullOrBlank()) {
                        visitorData = returnedVisitor
                    }

                    logDebug(url, true, code, "OK (${rawBody.length} bytes)")
                    return@withContext NetResult.Success(json)
                } else {
                    val snippet = rawBody.take(300)
                    Log.w(TAG, "safeCall non-200 url: $url status: $code body: $snippet")
                    logDebug(url, false, code, snippet)

                    if (code in 500..599 && attempt < maxAttempts) {
                        delay(250L * attempt)
                        continue
                    }
                    return@withContext NetResult.Error("HTTP $code", code, snippet)
                }
            } catch (e: IOException) {
                lastException = e
                Log.w(TAG, "safeCall IOException url: $url attempt $attempt: ${e.message}")
                logDebug(url, false, null, "IOException: ${e.message}")
                if (attempt < maxAttempts) {
                    delay(300L * attempt)
                    continue
                }
            } catch (e: Exception) {
                Log.w(TAG, "safeCall unexpected exception url: $url: ${e.message}", e)
                logDebug(url, false, null, "Exception: ${e.message}")
                return@withContext NetResult.Error("Unexpected failure", null, null, e)
            }
        }
        NetResult.Error("Failed after $maxAttempts attempts: ${lastException?.message}", null, null, lastException)
    }

    /**
     * Fetches real YouTube Music Home Quick Picks and Charts with multi-tier fallback.
     */
    suspend fun getHomeCharts(): NetResult<List<TrackEntity>> = withContext(Dispatchers.IO) {
        // Tier 1: FEmusic_home (Quick picks)
        val homeBody = JSONObject().apply {
            put("context", buildYtMusicContext())
            put("browseId", "FEmusic_home")
        }
        val homeRes = safeCall("$YTM_API_BASE/browse?prettyPrint=false", homeBody)
        if (homeRes is NetResult.Success) {
            val tracks = BrowseParser.parseHomeTracks(homeRes.data)
            if (tracks.isNotEmpty()) {
                return@withContext NetResult.Success(tracks)
            }
        }

        // Tier 2: FEmusic_charts
        val chartsBody = JSONObject().apply {
            put("context", buildYtMusicContext())
            put("browseId", "FEmusic_charts")
        }
        val chartsRes = safeCall("$YTM_API_BASE/browse?prettyPrint=false", chartsBody)
        if (chartsRes is NetResult.Success) {
            val tracks = BrowseParser.parseChartsTracks(chartsRes.data)
            if (tracks.isNotEmpty()) {
                return@withContext NetResult.Success(tracks)
            }
        }

        // Tier 3: Search popular charts
        val searchBody = JSONObject().apply {
            put("context", buildYtMusicContext())
            put("query", "Top Hits 2024")
            put("params", FILTER_SONGS)
        }
        val searchRes = safeCall("$YTM_API_BASE/search?prettyPrint=false", searchBody)
        if (searchRes is NetResult.Success) {
            val group = SearchParser.parseSearch(searchRes.data, "Top Hits")
            if (group.songs.isNotEmpty()) {
                return@withContext NetResult.Success(group.songs)
            }
        }

        NetResult.Empty("No home or chart tracks available")
    }

    /**
     * Real-time search suggestions from music.youtube.com
     */
    suspend fun getSearchSuggestions(query: String): List<String> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        val body = JSONObject().apply {
            put("context", buildYtMusicContext())
            put("input", query)
        }
        val res = safeCall("$YTM_API_BASE/music/get_search_suggestions?prettyPrint=false", body)
        if (res is NetResult.Success) {
            val contents = res.data.optJSONArray("contents") ?: return@withContext emptyList()
            val list = mutableListOf<String>()
            for (i in 0 until contents.length()) {
                val item = contents.optJSONObject(i)
                val runs = item?.optJSONObject("searchSuggestionRenderer")
                    ?.optJSONObject("suggestion")
                    ?.optJSONArray("runs")
                if (runs != null && runs.length() > 0) {
                    val text = (0 until runs.length()).joinToString("") { runs.optJSONObject(it)?.optString("text") ?: "" }
                    if (text.isNotBlank()) list.add(text)
                }
            }
            return@withContext list
        }
        emptyList()
    }

    /**
     * Full search with optional server-side filter params
     */
    suspend fun search(query: String, filterParams: String? = null): NetResult<SearchResultGroup> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext NetResult.Empty("Empty search query")
        val body = JSONObject().apply {
            put("context", buildYtMusicContext())
            put("query", query)
            if (!filterParams.isNullOrBlank()) {
                put("params", filterParams)
            }
        }
        val res = safeCall("$YTM_API_BASE/search?prettyPrint=false", body)
        return@withContext when (res) {
            is NetResult.Success -> {
                val group = SearchParser.parseSearch(res.data, query)
                if (group.songs.isEmpty() && group.artists.isEmpty() && group.albums.isEmpty() && group.topResultArtist == null) {
                    NetResult.Empty("No results for '$query'")
                } else {
                    NetResult.Success(group)
                }
            }
            is NetResult.Error -> res
            is NetResult.Empty -> res
        }
    }

    /**
     * Fetch artist page with discography, albums, and continuation
     */
    suspend fun getArtistPage(browseId: String, artistName: String): NetResult<ArtistPageData> = withContext(Dispatchers.IO) {
        if (browseId.isBlank() || !browseId.startsWith("UC")) {
            // Search artist songs directly if not a valid UC browseId
            val searchRes = search("$artistName songs", FILTER_SONGS)
            if (searchRes is NetResult.Success) {
                val tracks = searchRes.data.songs.filter { it.artist.contains(artistName, ignoreCase = true) }
                    .ifEmpty { searchRes.data.songs }
                val artist = InnerTubeArtist(browseId.ifBlank { "artist_${artistName.hashCode()}" }, artistName, "", "Artist")
                return@withContext NetResult.Success(ArtistPageData(artist, tracks))
            }
            return@withContext NetResult.Empty("Artist not found")
        }

        val body = JSONObject().apply {
            put("context", buildYtMusicContext())
            put("browseId", browseId)
        }
        val res = safeCall("$YTM_API_BASE/browse?prettyPrint=false", body)
        return@withContext when (res) {
            is NetResult.Success -> {
                val pageData = ArtistParser.parseArtistPage(res.data, browseId, artistName)
                if (pageData.songs.isEmpty()) {
                    // Enrich with search if browse section is thin
                    val searchSongs = search("$artistName songs", FILTER_SONGS)
                    if (searchSongs is NetResult.Success) {
                        NetResult.Success(pageData.copy(songs = searchSongs.data.songs))
                    } else {
                        NetResult.Success(pageData)
                    }
                } else {
                    NetResult.Success(pageData)
                }
            }
            is NetResult.Error -> res
            is NetResult.Empty -> res
        }
    }

    // ------------------------------------------------------------------
    // Album dan lirik
    // ------------------------------------------------------------------

    suspend fun getAlbumTracks(album: InnerTubeAlbum): NetResult<List<TrackEntity>> = withContext(Dispatchers.IO) {
        var lastError: NetResult<List<TrackEntity>> = NetResult.Empty("Album tidak tersedia")

        // 1. Halaman album langsung bila id-nya id album yang valid
        if (album.id.startsWith("MPRE")) {
            when (val res = browseAlbum(album.id, album)) {
                is NetResult.Success -> return@withContext res
                is NetResult.Error -> lastError = res
                is NetResult.Empty -> lastError = res
            }
        }

        // 2. Cari ulang albumnya (id bisa kosong atau berupa penanda sementara), lalu buka halamannya
        val query = "${album.title} ${album.artist}".trim()
        val searchRes = search(query)
        val found = (searchRes as? NetResult.Success)?.data
        if (found != null) {
            val match = found.albums.firstOrNull { it.id.startsWith("MPRE") && it.id != album.id &&
                it.title.equals(album.title, ignoreCase = true) }
                ?: found.albums.firstOrNull { it.id.startsWith("MPRE") && it.id != album.id &&
                    it.title.contains(album.title, ignoreCase = true) }
            if (match != null) {
                when (val res = browseAlbum(match.id, album)) {
                    is NetResult.Success -> return@withContext res
                    is NetResult.Error -> lastError = res
                    is NetResult.Empty -> lastError = res
                }
            }

            // 3. Terakhir: lagu hasil pencarian yang nama albumnya sama
            val songs = found.songs.filter { it.album.equals(album.title, ignoreCase = true) }
                .map { it.copy(coverUrl = album.coverUrl.ifBlank { it.coverUrl }) }
            if (songs.isNotEmpty()) return@withContext NetResult.Success(songs)
        }
        lastError
    }

    private suspend fun browseAlbum(browseId: String, album: InnerTubeAlbum): NetResult<List<TrackEntity>> {
        val body = JSONObject().apply {
            put("context", buildYtMusicContext())
            put("browseId", browseId)
        }
        return when (val res = safeCall("$YTM_API_BASE/browse?prettyPrint=false", body)) {
            is NetResult.Success -> {
                val tracks = com.example.innertube.parser.AlbumParser.parseTracks(res.data, album.title, album.artist, album.coverUrl)
                if (tracks.isEmpty()) NetResult.Empty("Album kosong") else NetResult.Success(tracks)
            }
            is NetResult.Error -> res
            is NetResult.Empty -> res
        }
    }

    /** Lirik teks biasa dari halaman lirik resmi (dicocokkan berdasarkan videoId, jadi akurat). */
    suspend fun getYtMusicLyrics(videoId: String): String? = withContext(Dispatchers.IO) {
        val nextBody = JSONObject().apply {
            put("context", buildYtMusicContext())
            put("videoId", videoId)
            put("isAudioOnly", true)
        }
        val nextRes = safeCall("$YTM_API_BASE/next?prettyPrint=false", nextBody)
        val nextJson = (nextRes as? NetResult.Success)?.data ?: return@withContext null
        val browseId = findLyricsBrowseId(nextJson) ?: return@withContext null

        val browseBody = JSONObject().apply {
            put("context", buildYtMusicContext())
            put("browseId", browseId)
        }
        val browseRes = safeCall("$YTM_API_BASE/browse?prettyPrint=false", browseBody)
        val json = (browseRes as? NetResult.Success)?.data ?: return@withContext null
        extractDescription(json)
    }

    private fun findLyricsBrowseId(node: Any?): String? {
        when (node) {
            is JSONObject -> {
                val id = node.optString("browseId")
                if (id.startsWith("MPLYt")) return id
                val keys = node.keys()
                while (keys.hasNext()) {
                    val r = findLyricsBrowseId(node.opt(keys.next()))
                    if (r != null) return r
                }
            }
            is JSONArray -> for (i in 0 until node.length()) {
                val r = findLyricsBrowseId(node.opt(i))
                if (r != null) return r
            }
        }
        return null
    }

    private fun extractDescription(node: Any?): String? {
        when (node) {
            is JSONObject -> {
                val shelf = node.optJSONObject("musicDescriptionShelfRenderer")
                if (shelf != null) {
                    val runs = shelf.optJSONObject("description")?.optJSONArray("runs")
                    if (runs != null) {
                        val sb = StringBuilder()
                        for (i in 0 until runs.length()) sb.append(runs.optJSONObject(i)?.optString("text").orEmpty())
                        val text = sb.toString().trim()
                        if (text.isNotBlank()) return text
                    }
                }
                val keys = node.keys()
                while (keys.hasNext()) {
                    val r = extractDescription(node.opt(keys.next()))
                    if (r != null) return r
                }
            }
            is JSONArray -> for (i in 0 until node.length()) {
                val r = extractDescription(node.opt(i))
                if (r != null) return r
            }
        }
        return null
    }

    // ------------------------------------------------------------------
    // Radio (lagu lanjutan yang nyambung)
    // ------------------------------------------------------------------

    /**
     * Radio dari satu lagu. Halaman pertama memakai playlistId `RDAMVM{videoId}`,
     * halaman berikutnya memakai token continuation dari respons sebelumnya.
     */
    suspend fun getRadio(videoId: String, continuation: String? = null): NetResult<com.example.innertube.parser.RadioPage> {
        val body = JSONObject().apply {
            put("context", buildYtMusicContext())
            if (continuation == null) {
                put("videoId", videoId)
                put("playlistId", "RDAMVM$videoId")
                put("isAudioOnly", true)
                put("tunerSettingValue", "AUTOMIX_SETTING_NORMAL")
                put(
                    "watchEndpointMusicSupportedConfigs",
                    JSONObject().put(
                        "watchEndpointMusicConfig",
                        JSONObject()
                            .put("hasPersistentPlaylistPanel", true)
                            .put("musicVideoType", "MUSIC_VIDEO_TYPE_ATV")
                    )
                )
            }
        }
        val url = if (continuation == null) {
            "$YTM_API_BASE/next?prettyPrint=false"
        } else {
            val token = java.net.URLEncoder.encode(continuation, "UTF-8")
            "$YTM_API_BASE/next?ctoken=$token&continuation=$token&type=next&prettyPrint=false"
        }
        return when (val res = safeCall(url, body)) {
            is NetResult.Success -> NetResult.Success(com.example.innertube.parser.RadioParser.parse(res.data))
            is NetResult.Error -> res
            is NetResult.Empty -> res
        }
    }

    // ------------------------------------------------------------------
    // Stream resolution
    // ------------------------------------------------------------------

    // Daftar klien dicoba berurutan. Versi klien sering usang, jadi semua nilai ada di satu tempat ini.
    private val streamClients: List<StreamClient> = listOf(
        // Url dari klien ini melayani permintaan terbuka (tanpa batas range) dan tanpa PO token.
        StreamClient(
            name = "VISIONOS",
            clientName = "VISIONOS",
            clientVersion = "1.02",
            clientId = "101",
            userAgent = VISIONOS_UA,
            apiBase = "https://www.youtube.com/youtubei/v1",
            extra = mapOf(
                "deviceMake" to "Apple",
                "deviceModel" to "RealityDevice17,1",
                "osName" to "visionOS",
                "osVersion" to "26.5.23O471"
            )
        ),
        StreamClient(
            name = "ANDROID_VR",
            clientName = "ANDROID_VR",
            clientVersion = "1.61.48",
            clientId = "28",
            userAgent = "com.google.android.apps.youtube.vr.oculus/1.61.48 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip",
            apiBase = "https://www.youtube.com/youtubei/v1",
            extra = mapOf(
                "deviceMake" to "Oculus",
                "deviceModel" to "Quest 3",
                "osName" to "Android",
                "osVersion" to "12L",
                "androidSdkVersion" to 32
            )
        ),
        StreamClient(
            name = "IOS",
            clientName = "IOS",
            clientVersion = "20.10.4",
            clientId = "5",
            userAgent = "com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)",
            apiBase = "https://www.youtube.com/youtubei/v1",
            extra = mapOf(
                "deviceMake" to "Apple",
                "deviceModel" to "iPhone16,2",
                "osName" to "iPhone",
                "osVersion" to "18.3.2.22D82"
            )
        ),
        StreamClient(
            name = "ANDROID_MUSIC",
            clientName = "ANDROID_MUSIC",
            clientVersion = "7.27.52",
            clientId = "21",
            userAgent = "com.google.android.apps.youtube.music/7.27.52 (Linux; U; Android 14; GB) gzip",
            apiBase = "https://music.youtube.com/youtubei/v1",
            extra = mapOf(
                "osName" to "Android",
                "osVersion" to "14"
            )
        ),
        StreamClient(
            name = "ANDROID",
            clientName = "ANDROID",
            clientVersion = "20.10.38",
            clientId = "3",
            userAgent = "com.google.android.youtube/20.10.38 (Linux; U; Android 11) gzip",
            apiBase = "https://www.youtube.com/youtubei/v1",
            extra = mapOf(
                "osName" to "Android",
                "osVersion" to "11"
            )
        ),
        StreamClient(
            name = "WEB_REMIX",
            clientName = "WEB_REMIX",
            clientVersion = WEB_REMIX_VERSION,
            clientId = "67",
            userAgent = DESKTOP_UA,
            apiBase = YTM_API_BASE,
            isWeb = true
        )
    )

    /** User-Agent yang harus dipakai pemutar untuk URL stream ini (sesuai klien pembuatnya). */
    fun userAgentForUrl(url: String): String? {
        val httpUrl = url.toHttpUrlOrNull() ?: return null
        if (!httpUrl.host.endsWith("googlevideo.com")) return null
        val c = httpUrl.queryParameter("c") ?: return null
        return streamClients.firstOrNull { it.clientName == c }?.userAgent
    }

    /** HTTP client untuk ExoPlayer dan download: otomatis memasang User-Agent yang cocok. */
    val playbackHttpClient: OkHttpClient = client.newBuilder()
        .readTimeout(30, TimeUnit.SECONDS)
        .addInterceptor(Interceptor { chain ->
            val original = chain.request()
            val ua = userAgentForUrl(original.url.toString())
            val request = if (ua != null) original.newBuilder().header("User-Agent", ua).build() else original
            chain.proceed(request)
        })
        .build()

    @Volatile
    var lastResolveReport: String = ""
        private set

    private fun note(report: StringBuilder, source: String, code: Int?, message: String) {
        report.append(source).append(": ").append(message).append('\n')
        Log.w(TAG, "resolve $source -> $message")
        logDebug("player/$source", false, code, message)
    }

    /**
     * Mencari URL audio yang benar-benar bisa diputar.
     * Urutan: beberapa klien InnerTube, lalu instance publik sebagai cadangan terakhir.
     * Setiap URL diperiksa dulu (Range 0-1) sebelum diberikan ke pemutar.
     */
    suspend fun resolveStream(
        videoId: String,
        excludeClients: Set<String> = emptySet(),
        forceRefresh: Boolean = false
    ): ResolvedStream? = withContext(Dispatchers.IO) {
        if (forceRefresh) {
            streamCache.remove(videoId)
        } else {
            val cached = streamCache[videoId]
            if (cached != null && System.currentTimeMillis() < cached.expiresAtMs) {
                return@withContext cached
            }
        }

        val report = StringBuilder()
        for (sc in streamClients) {
            if (sc.name in excludeClients) continue
            val result = tryClient(sc, videoId, report)
            if (result != null) {
                streamCache[videoId] = result
                lastResolveReport = report.append(sc.name).append(": OK").toString()
                logDebug("player/${sc.name}", true, 200, "stream ok $videoId")
                return@withContext result
            }
        }

        val fallback = resolveViaPublicInstances(videoId, excludeClients, report)
        if (fallback != null) {
            streamCache[videoId] = fallback
            lastResolveReport = report.append(fallback.client).append(": OK").toString()
            return@withContext fallback
        }

        lastResolveReport = report.toString()
        Log.w(TAG, "resolveStream gagal untuk $videoId:\n$lastResolveReport")
        null
    }

    /** Kompatibilitas: dipakai DownloadManager. */
    suspend fun resolveAudioStream(videoId: String): String =
        resolveStream(videoId)?.url ?: ""

    private fun tryClient(sc: StreamClient, videoId: String, report: StringBuilder): ResolvedStream? {
        try {
            val locale = Locale.getDefault()
            val clientJson = JSONObject().apply {
                put("clientName", sc.clientName)
                put("clientVersion", sc.clientVersion)
                put("hl", if (locale.language.isNotBlank()) locale.language else "en")
                put("gl", if (locale.country.isNotBlank()) locale.country else "US")
                put("userAgent", sc.userAgent)
                for ((k, v) in sc.extra) put(k, v)
                if (visitorData.isNotBlank()) put("visitorData", visitorData)
            }
            val body = JSONObject().apply {
                put("context", JSONObject().apply { put("client", clientJson) })
                put("videoId", videoId)
                put("contentCheckOk", true)
                put("racyCheckOk", true)
            }
            val headers = Headers.Builder()
                .add("Content-Type", "application/json")
                .add("User-Agent", sc.userAgent)
                .add("X-YouTube-Client-Name", sc.clientId)
                .add("X-YouTube-Client-Version", sc.clientVersion)
            if (sc.isWeb) {
                headers.add("Origin", "https://music.youtube.com")
                headers.add("Referer", "https://music.youtube.com/")
            }
            if (visitorData.isNotBlank()) headers.add("X-Goog-Visitor-Id", visitorData)

            val request = Request.Builder()
                .url("${sc.apiBase}/player?prettyPrint=false")
                .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
                .headers(headers.build())
                .build()

            val (code, raw) = client.newCall(request).execute().use { response ->
                Pair(response.code, response.body?.string() ?: "")
            }
            if (code !in 200..299) {
                note(report, sc.name, code, "HTTP $code ${raw.take(120)}")
                return null
            }

            val json = JSONObject(raw)
            val returnedVisitor = json.optJSONObject("responseContext")?.optString("visitorData")
            if (!returnedVisitor.isNullOrBlank() && visitorData.isBlank()) {
                visitorData = returnedVisitor
            }

            val playability = json.optJSONObject("playabilityStatus")
            val status = playability?.optString("status").orEmpty()
            if (status != "OK") {
                val reason = playability?.optString("reason").orEmpty()
                note(report, sc.name, code, "status=$status $reason")
                return null
            }

            val streaming = json.optJSONObject("streamingData")
            if (streaming == null) {
                note(report, sc.name, code, "tanpa streamingData")
                return null
            }

            val candidates = mutableListOf<ResolvedStream>()
            var withoutUrl = 0
            val adaptive = streaming.optJSONArray("adaptiveFormats")
            if (adaptive != null) {
                for (i in 0 until adaptive.length()) {
                    val fmt = adaptive.optJSONObject(i) ?: continue
                    val mime = fmt.optString("mimeType")
                    if (!mime.startsWith("audio/")) continue
                    val url = fmt.optString("url")
                    if (url.isBlank()) {
                        withoutUrl++
                        continue
                    }
                    candidates.add(
                        ResolvedStream(
                            url = url,
                            client = sc.name,
                            expiresAtMs = parseExpireParam(url),
                            mimeType = mime,
                            bitrate = fmt.optInt("bitrate", 0)
                        )
                    )
                }
            }

            if (candidates.isEmpty()) {
                val sabr = if (streaming.has("serverAbrStreamingUrl")) " (hanya SABR)" else ""
                note(report, sc.name, code, "tidak ada URL audio langsung, tanpa url=$withoutUrl$sabr")
                return null
            }

            val ordered = candidates.sortedWith(
                compareByDescending<ResolvedStream> { it.mimeType.startsWith("audio/mp4") }
                    .thenByDescending { it.bitrate }
            )
            for (candidate in ordered.take(2)) {
                val probe = probeUrl(candidate.url, sc.userAgent)
                if (probe == 200 || probe == 206) return candidate
                note(report, sc.name, probe, "URL ditolak server (HTTP $probe)")
            }
            return null
        } catch (e: Exception) {
            note(report, sc.name, null, "exception ${e.javaClass.simpleName}: ${e.message}")
            return null
        }
    }

    private fun probeUrl(url: String, userAgent: String?): Int {
        return try {
            val builder = Request.Builder().url(url).header("Range", "bytes=0-1")
            if (userAgent != null) builder.header("User-Agent", userAgent)
            client.newCall(builder.build()).execute().use { it.code }
        } catch (e: Exception) {
            -1
        }
    }

    private fun httpGetText(url: String, timeoutSec: Long): String? {
        return try {
            val req = Request.Builder().url(url).header("User-Agent", DESKTOP_UA).build()
            client.newBuilder().callTimeout(timeoutSec, TimeUnit.SECONDS).build()
                .newCall(req).execute().use { r ->
                    if (r.isSuccessful) r.body?.string() else null
                }
        } catch (e: Exception) {
            null
        }
    }

    /** Cadangan terakhir bila semua klien resmi gagal. Instance publik bisa mati kapan saja. */
    private fun resolveViaPublicInstances(
        videoId: String,
        exclude: Set<String>,
        report: StringBuilder
    ): ResolvedStream? {
        val deadline = System.currentTimeMillis() + 15_000L

        if ("PIPED" !in exclude) {
            val apis = mutableListOf<String>()
            val listRaw = httpGetText("https://piped-instances.kavin.rocks/", 4)
            if (listRaw != null) {
                try {
                    val arr = JSONArray(listRaw)
                    for (i in 0 until arr.length()) {
                        val api = arr.optJSONObject(i)?.optString("api_url").orEmpty()
                        if (api.startsWith("https://")) apis.add(api)
                    }
                } catch (_: Exception) {
                }
            } else {
                note(report, "PIPED", null, "daftar instance tidak terjangkau")
            }
            for (api in apis.shuffled().take(3)) {
                if (System.currentTimeMillis() > deadline) break
                val raw = httpGetText("$api/streams/$videoId", 5)
                if (raw == null) {
                    note(report, "PIPED", null, "$api tidak merespons")
                    continue
                }
                try {
                    val streams = JSONObject(raw).optJSONArray("audioStreams")
                    if (streams == null || streams.length() == 0) {
                        note(report, "PIPED", null, "$api tanpa audioStreams")
                        continue
                    }
                    val list = mutableListOf<ResolvedStream>()
                    for (i in 0 until streams.length()) {
                        val st = streams.optJSONObject(i) ?: continue
                        val url = st.optString("url")
                        if (url.isBlank()) continue
                        list.add(
                            ResolvedStream(
                                url = url,
                                client = "PIPED",
                                expiresAtMs = System.currentTimeMillis() + 2 * 3600 * 1000L,
                                mimeType = st.optString("mimeType", "audio/mp4"),
                                bitrate = st.optInt("bitrate", 0)
                            )
                        )
                    }
                    val best = list.sortedWith(
                        compareByDescending<ResolvedStream> { it.mimeType.startsWith("audio/mp4") }
                            .thenByDescending { it.bitrate }
                    ).firstOrNull()
                    if (best != null) {
                        val probe = probeUrl(best.url, null)
                        if (probe == 200 || probe == 206) return best
                        note(report, "PIPED", probe, "$api URL ditolak (HTTP $probe)")
                    }
                } catch (e: Exception) {
                    note(report, "PIPED", null, "$api parse gagal: ${e.message}")
                }
            }
        }

        if ("INVIDIOUS" !in exclude && System.currentTimeMillis() < deadline) {
            val bases = mutableListOf<String>()
            val listRaw = httpGetText("https://api.invidious.io/instances.json?sort_by=health", 4)
            if (listRaw != null) {
                try {
                    val arr = JSONArray(listRaw)
                    for (i in 0 until arr.length()) {
                        val entry = arr.optJSONArray(i) ?: continue
                        val info = entry.optJSONObject(1) ?: continue
                        val uri = info.optString("uri")
                        if (info.optBoolean("api", false) && uri.startsWith("https://")) bases.add(uri)
                    }
                } catch (_: Exception) {
                }
            } else {
                note(report, "INVIDIOUS", null, "daftar instance tidak terjangkau")
            }
            for (base in bases.take(3)) {
                if (System.currentTimeMillis() > deadline) break
                val raw = httpGetText("$base/api/v1/videos/$videoId?fields=adaptiveFormats&local=true", 5)
                if (raw == null) {
                    note(report, "INVIDIOUS", null, "$base tidak merespons")
                    continue
                }
                try {
                    val formats = JSONObject(raw).optJSONArray("adaptiveFormats")
                    if (formats == null) {
                        note(report, "INVIDIOUS", null, "$base tanpa adaptiveFormats")
                        continue
                    }
                    val list = mutableListOf<ResolvedStream>()
                    for (i in 0 until formats.length()) {
                        val f = formats.optJSONObject(i) ?: continue
                        val type = f.optString("type")
                        if (!type.startsWith("audio/")) continue
                        var url = f.optString("url")
                        if (url.isBlank()) continue
                        if (url.startsWith("/")) url = base + url
                        list.add(
                            ResolvedStream(
                                url = url,
                                client = "INVIDIOUS",
                                expiresAtMs = System.currentTimeMillis() + 2 * 3600 * 1000L,
                                mimeType = type,
                                bitrate = f.optString("bitrate").toIntOrNull() ?: 0
                            )
                        )
                    }
                    val best = list.sortedWith(
                        compareByDescending<ResolvedStream> { it.mimeType.startsWith("audio/mp4") }
                            .thenByDescending { it.bitrate }
                    ).firstOrNull()
                    if (best != null) {
                        val probe = probeUrl(best.url, null)
                        if (probe == 200 || probe == 206) return best
                        note(report, "INVIDIOUS", probe, "$base URL ditolak (HTTP $probe)")
                    }
                } catch (e: Exception) {
                    note(report, "INVIDIOUS", null, "$base parse gagal: ${e.message}")
                }
            }
        }
        return null
    }

    private fun parseExpireParam(url: String): Long {
        val expireSec = url.toHttpUrlOrNull()?.queryParameter("expire")?.toLongOrNull()
        return if (expireSec != null) {
            (expireSec * 1000L) - 60_000L // sisakan 1 menit sebelum kedaluwarsa
        } else {
            System.currentTimeMillis() + 3 * 3600 * 1000L
        }
    }
}

private const val VISIONOS_UA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Safari/605.1.15"

data class ResolvedStream(
    val url: String,
    val client: String,
    val expiresAtMs: Long,
    val mimeType: String,
    val bitrate: Int
)

private class StreamClient(
    val name: String,
    val clientName: String,
    val clientVersion: String,
    val clientId: String,
    val userAgent: String,
    val apiBase: String,
    val extra: Map<String, Any> = emptyMap(),
    val isWeb: Boolean = false
)
