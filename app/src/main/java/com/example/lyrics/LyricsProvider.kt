package com.example.lyrics

import android.text.Html
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.text.Normalizer
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/** Hasil pencarian lirik. [lrc] berformat LRC (atau teks biasa), [syncedJson] berisi data per kata bila ada. */
data class LyricsResult(
    val lrc: String,
    val syncedJson: String,
    val synced: Boolean,
    val source: String,
    val durationSec: Double = 0.0
)

/**
 * Mencari lirik dari beberapa sumber. Setiap hasil dari sumber berbasis pencarian harus lolos
 * pemeriksaan judul, artis, dan durasi supaya tidak menampilkan lirik lagu lain.
 */
class LyricsProvider {
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val ua = "UnMusic/1.0 (Android)"
    private val browserUa =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"

    /** Pengambil lirik teks dari halaman lirik resmi berdasarkan videoId. Dipasang oleh Application. */
    var ytMusicLyrics: (suspend (String) -> String?)? = null

    /** Kompatibilitas lama: hanya mengembalikan teks lirik. */
    suspend fun fetchLyrics(title: String, artist: String, durationSec: Long = 0): String? =
        fetch(title, artist, durationSec, "")?.lrc

    /**
     * Alur pencarian lirik sama seperti versi web UnMusic:
     * 1) LRCLIB `get` lalu pencarian berskor, 2) API BetterLyrics, 3) sumber lain bergantian.
     * Setiap sumber yang gagal atau kosong tidak menghentikan pencarian, langsung lanjut ke sumber berikutnya.
     */
    suspend fun fetch(
        title: String,
        artist: String,
        durationSec: Long,
        videoId: String,
        album: String = ""
    ): LyricsResult? = withContext(Dispatchers.IO) {
        val t = webCleanTitle(title)
        val cleanedArtist = webNormalizeArtist(artist)
        val a = if (cleanedArtist.lowercase() in placeholderArtists) "" else cleanedArtist
        val altTitle = cleanTitle(title)
        val altArtist = cleanArtist(artist).let { if (it.lowercase() in placeholderArtists) "" else it }

        val result = coroutineScope {
            // Sumber cadangan dimulai bersamaan supaya tidak menambah waktu tunggu
            val simp = async { safely { if (videoId.isNotBlank()) simpMusic(videoId) else null } }
            val boidu = async { safely { betterLyrics(altTitle, altArtist, durationSec) } }
            val kugouJob = async { safely { kugou(altTitle, altArtist, durationSec) } }
            val netEaseJob = async { safely { netEase(altTitle, altArtist, durationSec) } }
            val qqJob = async { safely { qqMusic(altTitle, altArtist, durationSec) } }
            val yt = async { safely { youtubeLyrics(videoId) } }
            val backups = listOf(simp, boidu, kugouJob, netEaseJob, qqJob)

            // 1. Persis seperti web: LRCLIB, lalu API BetterLyrics
            var best: LyricsResult? = safely { lrclibWebStyle(t, a, album, durationSec) }
            if (best == null || !best.synced) {
                val better = safely { betterLyricsWeb(t, a, durationSec) }
                if (better != null && (best == null || better.synced)) best = better
            }
            if (best != null && best.synced) {
                (backups + yt).forEach { it.cancel() }
                return@coroutineScope best
            }

            // 2. Belum ada versi tersinkron: sumber lain dicoba bergantian sesuai urutan
            val backupResults = mutableListOf<LyricsResult>()
            for (job in backups) {
                val r = withTimeoutOrNull(7000L) { job.await() } ?: continue
                if (r.synced) {
                    yt.cancel()
                    return@coroutineScope r
                }
                backupResults.add(r)
            }

            // 3. Teks biasa (statis) bila tidak ada yang tersinkron
            if (best != null) return@coroutineScope best
            val ytText = withTimeoutOrNull(6000L) { yt.await() }
            if (ytText != null) return@coroutineScope ytText
            backupResults.firstOrNull()
        }

        val finalResult = result ?: try { lyricsOvh(t, a) } catch (e: Exception) { null }
        // Sumber dicatat sebagai tag LRC [by:...] agar bisa ditampilkan dan diperiksa
        finalResult?.let { it.copy(lrc = "[by:${it.source}]\n${it.lrc}") }
    }

    // ------------------------------------------------------------ meniru web

    private val webBadTitleBrackets = Regex(
        "\\s*[\\(\\[][^\\)\\]]*(official|video|audio|lyric|lyrics|mv|m/v|hd|4k|visualizer|performance|live session|color coded|sub\\s*indo|terjemahan)[^\\)\\]]*[\\)\\]]",
        RegexOption.IGNORE_CASE
    )

    /** Sama dengan cleanTitle di web: buang tanda kurung berisi "official", "video", dst. */
    private fun webCleanTitle(raw: String): String {
        var t = raw.trim()
        t = t.replace(webBadTitleBrackets, "")
        t = t.replace(Regex("\\s*[|｜]\\s*(official|lyric|audio|video)[^|]*$", RegexOption.IGNORE_CASE), "")
        t = t.replace(Regex("\\s*-\\s*(official\\s*)?(music\\s*)?(video|audio|lyric video|lyrics)\\s*$", RegexOption.IGNORE_CASE), "")
        t = t.replace(
            Regex("\\s*\\b(official\\s*(music\\s*)?video|official\\s*audio|lyric\\s*video|audio\\s*only)\\b\\s*", RegexOption.IGNORE_CASE),
            " "
        )
        t = t.replace(Regex("\\s*\\bft\\.?\\b|\\s*\\bfeat\\.?\\b", RegexOption.IGNORE_CASE), " feat. ")
        t = t.replace(Regex("feat\\.\\s*[.,·]+\\s*", RegexOption.IGNORE_CASE), "feat. ")
        t = t.replace(Regex("([(\\[])\\s+"), "$1").replace(Regex("\\s+([)\\]])"), "$1")
        t = t.replace(Regex("\\(\\s*\\)|\\[\\s*\\]"), "")
        t = t.replace(Regex("\\s{2,}"), " ").replace(Regex("[\\s\\-–—|]+$"), "").trim()
        return if (t.isNotEmpty()) t else raw.trim()
    }

    /** Sama dengan normalizeArtist di web: buang "- Topic", "VEVO", "Official Channel". */
    private fun webNormalizeArtist(raw: String): String {
        return raw
            .replace(Regex("\\s*-\\s*Topic$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("VEVO$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*Official(\\s*Channel)?$", RegexOption.IGNORE_CASE), "")
            .trim()
    }

    private fun wordTokens(s: String): List<String> =
        s.lowercase().replace(Regex("[^\\p{L}\\p{N}\\s]"), " ").split(Regex("\\s+")).filter { it.isNotEmpty() }

    /** Kemiripan kata (koefisien Dice), sama dengan fungsi kemiripan di web. */
    private fun wordSimilarity(a: String, b: String): Double {
        val ta = wordTokens(a)
        val tb = wordTokens(b)
        if (ta.isEmpty() || tb.isEmpty()) return 0.0
        val counts = HashMap<String, Int>()
        for (w in tb) counts[w] = (counts[w] ?: 0) + 1
        var common = 0
        for (w in ta) {
            val c = counts[w] ?: 0
            if (c > 0) {
                common++
                counts[w] = c - 1
            }
        }
        return 2.0 * common / (ta.size + tb.size)
    }

    /** Skor kandidat LRCLIB: sama dengan skor di web (tersinkron +100, selisih durasi mengurangi, kemiripan judul/artis menambah). */
    private fun webScore(o: JSONObject, title: String, artist: String, durationSec: Long): Double {
        var score = 0.0
        if (o.optString("syncedLyrics").isNotBlank()) score += 100.0
        else if (o.optString("plainLyrics").isNotBlank()) score += 10.0
        val d = o.optDouble("duration", 0.0)
        if (durationSec > 0 && d > 0.0) score -= minOf(80.0, abs(d - durationSec) * 1.5)
        score += wordSimilarity(o.optString("trackName"), title) * 40.0
        score += wordSimilarity(o.optString("artistName"), artist) * 30.0
        if (o.optBoolean("instrumental", false)) score -= 50.0
        return score
    }

    private fun parseLrclibWeb(json: JSONObject?): LyricsResult? {
        if (json == null) return null
        val dur = json.optDouble("duration", 0.0)
        val synced = json.optString("syncedLyrics")
        if (synced.isNotBlank()) return LyricsResult(synced, "", true, "LRCLIB", dur)
        val plain = json.optString("plainLyrics")
        if (plain.isNotBlank()) return LyricsResult(plain, "", false, "LRCLIB", dur)
        return null
    }

    private fun lrclibWebStyle(title: String, artist: String, album: String, durationSec: Long): LyricsResult? {
        if (title.isBlank()) return null

        if (artist.isNotBlank()) {
            val base = "https://lrclib.net/api/get?track_name=${enc(title)}&artist_name=${enc(artist)}"
            val dur = if (durationSec > 0) "&duration=$durationSec" else ""
            val hasAlbum = album.isNotBlank() && album != "Single"
            val urls = mutableListOf<String>()
            if (hasAlbum) urls.add("$base&album_name=${enc(album)}$dur")
            urls.add("$base$dur")
            urls.add(base)
            for (url in urls) {
                val body = httpGet(url) ?: continue
                val parsed = try { parseLrclibWeb(JSONObject(body)) } catch (e: Exception) { null }
                if (parsed != null) return parsed
            }
        }

        val queries = mutableListOf<String>()
        if (artist.isNotBlank()) queries.add("track_name=${enc(title)}&artist_name=${enc(artist)}")
        queries.add("q=${enc("$title $artist".trim())}")
        queries.add("q=${enc(title)}")

        for (q in queries) {
            val body = httpGet("https://lrclib.net/api/search?$q") ?: continue
            val arr = try { JSONArray(body) } catch (e: Exception) { continue }
            if (arr.length() == 0) continue
            var best: JSONObject? = null
            var bestScore = Double.NEGATIVE_INFINITY
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val sc = webScore(o, title, artist, durationSec)
                if (sc > bestScore) {
                    bestScore = sc
                    best = o
                }
            }
            val parsed = parseLrclibWeb(best)
            if (parsed != null) return parsed
        }
        return null
    }

    /** API BetterLyrics yang dipakai web (lyrics.api.dacubeking.com). */
    private fun betterLyricsWeb(title: String, artist: String, durationSec: Long): LyricsResult? {
        if (title.isBlank()) return null
        val params = "s=${enc(title)}&a=${enc(artist)}&song=${enc(title)}&artist=${enc(artist)}" +
            if (durationSec > 0) "&d=$durationSec" else ""
        for (path in listOf("/getLyrics", "/")) {
            val body = httpGet("https://lyrics.api.dacubeking.com$path?$params") ?: continue
            val parsed = try { parseBetterLyricsWeb(body) } catch (e: Exception) { null }
            if (parsed != null) return parsed
        }
        return null
    }

    private fun parseTextLyrics(text: String, allowPlain: Boolean = true): LyricsResult? {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || lyricsLookLikeHtml(trimmed)) return null
        return if (Regex("\\[\\d{1,3}:\\d{2}").containsMatchIn(trimmed)) {
            LyricsResult(trimmed, "", true, "BetterLyrics")
        } else if (allowPlain) {
            LyricsResult(trimmed, "", false, "BetterLyrics")
        } else {
            null
        }
    }

    private fun parseBetterLyricsWeb(body: String): LyricsResult? {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return null
        val root: Any = when {
            trimmed.startsWith("[") -> JSONArray(trimmed)
            trimmed.startsWith("{") -> JSONObject(trimmed)
            else -> return parseTextLyrics(trimmed, allowPlain = false)
        }
        val list: Any? = when (root) {
            is JSONArray -> root
            is JSONObject -> {
                val l = root.opt("lyrics")
                if (l != null && l != JSONObject.NULL) l else {
                    val s = root.opt("syncedLyrics")
                    if (s != null && s != JSONObject.NULL) s else {
                        val p = root.opt("plainLyrics")
                        if (p != null && p != JSONObject.NULL) p else null
                    }
                }
            }
            else -> null
        }
        if (list is String) return parseTextLyrics(list)
        if (list !is JSONArray) return null

        val lines = mutableListOf<LyricLine>()
        for (i in 0 until list.length()) {
            val o = list.optJSONObject(i) ?: continue
            val timeMs: Long = when {
                o.has("startTimeMs") -> o.optDouble("startTimeMs", Double.NaN).let { if (it.isNaN()) -1L else it.toLong() }
                o.has("startMs") -> o.optDouble("startMs", Double.NaN).let { if (it.isNaN()) -1L else it.toLong() }
                o.has("time") -> o.optDouble("time", Double.NaN).let {
                    if (it.isNaN()) -1L else if (abs(it) > 60) it.toLong() else (it * 1000).toLong()
                }
                else -> -1L
            }
            if (timeMs < 0) continue

            val wordsArr = o.optJSONArray("words") ?: o.optJSONArray("syllables")
            val words = mutableListOf<LyricWord>()
            if (wordsArr != null) {
                for (j in 0 until wordsArr.length()) {
                    val w = wordsArr.optJSONObject(j) ?: continue
                    val wt = w.optString("text").ifBlank { w.optString("word") }.trim()
                    val ws = if (w.has("startTimeMs")) w.optDouble("startTimeMs", -1.0).toLong()
                    else (w.optDouble("time", -1.0) * (if (abs(w.optDouble("time", 0.0)) > 60) 1.0 else 1000.0)).toLong()
                    if (wt.isNotEmpty() && ws >= 0) words.add(LyricWord(wt, ws, 0L))
                }
            }
            val directText = o.optString("text").ifBlank {
                val w = o.opt("words")
                if (w is String) w else ""
            }
            val text = directText.trim().ifBlank { words.joinToString(" ") { it.word } }
            if (text.isBlank()) continue
            // durasi tiap kata = jarak ke kata berikutnya
            val timed = words.mapIndexed { idx, w ->
                val next = words.getOrNull(idx + 1)?.startMs
                LyricWord(w.word, w.startMs, if (next != null) (next - w.startMs).coerceAtLeast(0L) else 400L)
            }
            lines.add(LyricLine(timeMs, text, timed))
        }
        if (lines.isEmpty()) return null
        lines.sortBy { it.timeMs }
        val hasWords = lines.any { it.words.isNotEmpty() }
        return LyricsResult(
            lrc = TtmlParser.toLrc(lines),
            syncedJson = if (hasWords) TtmlParser.toJson(lines) else "",
            synced = true,
            source = "BetterLyrics"
        )
    }

    private suspend fun <T> safely(block: suspend () -> T?): T? {
        return try {
            val r = block()
            // Hasil yang ternyata halaman HTML ditolak agar sumber berikutnya dicoba
            if (r is LyricsResult && lyricsLookLikeHtml(r.lrc)) null else r
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "sumber lirik gagal: ${e.message}")
            null
        }
    }

    private fun sameRecording(r: LyricsResult, durationSec: Long): Boolean =
        r.durationSec > 0.0 && durationSec > 0 && abs(r.durationSec - durationSec) <= 2.0

    /** Dua hasil dianggap sepakat bila baris pertama, baris terakhir, dan jumlah barisnya berdekatan. */
    private fun pickAgreeing(results: List<LyricsResult>): LyricsResult? {
        if (results.size < 2) return null
        for (i in results.indices) {
            for (j in i + 1 until results.size) {
                if (timingAgrees(results[i].lrc, results[j].lrc)) return results[i]
            }
        }
        return null
    }

    private fun timingAgrees(a: String, b: String): Boolean {
        val la = LyricsParser.parseLrc(a)
        val lb = LyricsParser.parseLrc(b)
        if (!LyricsParser.isSynced(la) || !LyricsParser.isSynced(lb)) return false
        if (abs(la.size - lb.size) > 4) return false
        val firstDiff = abs(la.first().timeMs - lb.first().timeMs)
        val lastDiff = abs(la.last().timeMs - lb.last().timeMs)
        return firstDiff <= 1500L && lastDiff <= 2500L
    }

    /** Membuang tag waktu sehingga lirik tampil statis. */
    private fun toPlain(r: LyricsResult): LyricsResult {
        val text = LyricsParser.parseLrc(r.lrc).joinToString("\n") { it.text }
        return LyricsResult(text.ifBlank { r.lrc }, "", false, r.source)
    }

    // ------------------------------------------------------------ sumber

    private fun lrclibAll(title: String, artist: String, rawTitle: String, durationSec: Long): LyricsResult? {
        var plain: LyricsResult? = null
        val attempts = mutableListOf<() -> LyricsResult?>()
        attempts.add { lrclibGet(title, artist, durationSec, "get") }
        attempts.add { lrclibGet(title, artist, durationSec, "get-cached") }
        // Tanpa parameter durasi: LRCLIB mewajibkan selisih maksimal 2 detik bila durasi dikirim
        attempts.add { lrclibGet(title, artist, 0L, "get") }
        if (artist.isBlank() && durationSec > 0) {
            // Artis tidak diketahui: cari lewat judul saja dengan durasi yang harus sangat dekat
            attempts.add { lrclibSearch(title, "", durationSec, 4.0) }
        }
        if (artist.isNotBlank()) {
            attempts.add { lrclibSearchStructured(title, artist, durationSec) }
            attempts.add { lrclibSearch(title, artist, durationSec) }
        }
        if (rawTitle.contains(" - ")) {
            val parts = rawTitle.split(" - ", limit = 2)
            val swappedArtist = cleanArtist(parts[0])
            val swappedTitle = cleanTitle(parts[1])
            attempts.add { lrclibGet(swappedTitle, swappedArtist, durationSec, "get") }
            attempts.add { lrclibSearch(swappedTitle, swappedArtist, durationSec) }
        }

        for (attempt in attempts) {
            val r = try { attempt() } catch (e: Exception) { null }
            if (r == null) continue
            if (r.synced) return r
            if (plain == null) plain = r
        }
        return plain
    }

    private fun lrclibGet(title: String, artist: String, durationSec: Long, endpoint: String, tol: Double = 20.0): LyricsResult? {
        if (artist.isBlank()) return null
        val url = "https://lrclib.net/api/$endpoint?track_name=${enc(title)}&artist_name=${enc(artist)}" +
            if (durationSec > 0) "&duration=$durationSec" else ""
        val body = httpGet(url) ?: return null
        val json = JSONObject(body)
        if (json.optBoolean("instrumental", false)) return null
        if (!titleMatches(title, json.optString("trackName"))) return null
        if (!artistMatches(artist, json.optString("artistName"))) return null
        if (!durationMatches(durationSec, json.optDouble("duration", 0.0), tol)) return null
        return fromLrclibJson(json)
    }

    private fun lrclibSearchStructured(title: String, artist: String, durationSec: Long, tol: Double = 20.0): LyricsResult? {
        val body = httpGet("https://lrclib.net/api/search?track_name=${enc(title)}&artist_name=${enc(artist)}") ?: return null
        return pickBest(JSONArray(body), title, artist, durationSec, tol)
    }

    private fun lrclibSearch(title: String, artist: String, durationSec: Long, tol: Double = 20.0): LyricsResult? {
        val body = httpGet("https://lrclib.net/api/search?q=${enc("$title $artist")}") ?: return null
        return pickBest(JSONArray(body), title, artist, durationSec, tol)
    }

    private fun pickBest(arr: JSONArray, title: String, artist: String, durationSec: Long, tol: Double = 20.0): LyricsResult? {
        var best: JSONObject? = null
        var bestScore = Double.NEGATIVE_INFINITY
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optBoolean("instrumental", false)) continue
            val hasSynced = o.optString("syncedLyrics").isNotBlank()
            if (!hasSynced && o.optString("plainLyrics").isBlank()) continue
            if (!titleMatches(title, o.optString("trackName"))) continue
            if (!artistMatches(artist, o.optString("artistName"))) continue
            val d = o.optDouble("duration", 0.0)
            if (!durationMatches(durationSec, d, tol)) continue

            var score = if (hasSynced) 100.0 else 0.0
            score += 40.0 * similarity(title, o.optString("trackName"))
            score += 20.0 * similarity(artist, o.optString("artistName"))
            if (durationSec > 0 && d > 0) score += (20.0 - abs(d - durationSec)).coerceAtLeast(0.0)
            if (score > bestScore) {
                bestScore = score
                best = o
            }
        }
        return best?.let { fromLrclibJson(it) }
    }

    private fun fromLrclibJson(json: JSONObject): LyricsResult? {
        val dur = json.optDouble("duration", 0.0)
        val synced = json.optString("syncedLyrics")
        if (synced.isNotBlank()) return LyricsResult(synced, "", true, "LRCLIB", dur)
        val plain = json.optString("plainLyrics")
        if (plain.isNotBlank()) return LyricsResult(plain, "", false, "LRCLIB", dur)
        return null
    }

    private fun simpMusic(videoId: String): LyricsResult? {
        val body = httpGet("https://api-lyrics.simpmusic.org/v1/${enc(videoId)}") ?: return null
        val data = JSONObject(body).optJSONArray("data") ?: return null
        for (i in 0 until data.length()) {
            val o = data.optJSONObject(i) ?: continue
            val synced = o.optString("syncedLyrics")
            if (synced.isNotBlank()) return LyricsResult(synced, "", true, "SimpMusic")
        }
        for (i in 0 until data.length()) {
            val o = data.optJSONObject(i) ?: continue
            val plain = o.optString("plainLyric").ifBlank { o.optString("plainLyrics") }
            if (plain.isNotBlank()) return LyricsResult(plain, "", false, "SimpMusic")
        }
        return null
    }

    private fun betterLyrics(title: String, artist: String, durationSec: Long): LyricsResult? {
        val url = "https://lyrics-api.boidu.dev/getLyrics?s=${enc(title)}&a=${enc(artist)}" +
            if (durationSec > 0) "&d=$durationSec" else ""
        val body = httpGet(url) ?: return null
        val ttml = if (body.trimStart().startsWith("{")) JSONObject(body).optString("ttml") else body
        if (ttml.isBlank() || !ttml.contains("<p")) return null
        val parsed = TtmlParser.parse(ttml)
        if (parsed.isEmpty()) return null
        return LyricsResult(
            lrc = TtmlParser.toLrc(parsed),
            syncedJson = TtmlParser.toJson(parsed),
            synced = true,
            source = "BetterLyrics"
        )
    }

    private fun kugou(title: String, artist: String, durationSec: Long): LyricsResult? {
        if (artist.isBlank()) return null
        val q = "$artist $title"
        val body = httpGet("https://mobileservice.kugou.com/api/v3/search/song?format=json&keyword=${enc(q)}&page=1&pagesize=6&showtype=1")
            ?: return null
        val infos = JSONObject(body).optJSONObject("data")?.optJSONArray("info") ?: return null
        for (i in 0 until infos.length()) {
            val o = infos.optJSONObject(i) ?: continue
            val hash = o.optString("hash")
            val durSec = o.optDouble("duration", 0.0)
            if (hash.isBlank()) continue
            val songName = o.optString("songname")
            val titleOk = titleMatches(title, songName) ||
                songName.split(" - ", "-").any { titleMatches(title, it.trim()) }
            if (!titleOk) continue
            if (!artistMatches(artist, o.optString("singername"))) continue
            if (!durationMatches(durationSec, durSec)) continue

            val searchBody = httpGet(
                "https://lyrics.kugou.com/search?ver=1&man=yes&client=pc&keyword=${enc(q)}&duration=${(durSec * 1000).toLong()}&hash=$hash"
            ) ?: continue
            val candidates = JSONObject(searchBody).optJSONArray("candidates") ?: continue
            if (candidates.length() == 0) continue
            val c = candidates.optJSONObject(0) ?: continue
            val id = c.optString("id")
            val key = c.optString("accesskey")
            if (id.isBlank() || key.isBlank()) continue

            val dl = httpGet("https://lyrics.kugou.com/download?ver=1&client=pc&id=$id&accesskey=$key&fmt=lrc&charset=utf8")
                ?: continue
            val content = JSONObject(dl).optString("content")
            if (content.isBlank()) continue
            val text = String(Base64.decode(content, Base64.DEFAULT), Charsets.UTF_8)
                .removePrefix("\uFEFF").trim()
            if (text.isBlank()) continue
            return LyricsResult(text, "", hasTimestamps(text), "KuGou", durSec)
        }
        return null
    }

    private fun netEase(title: String, artist: String, durationSec: Long): LyricsResult? {
        if (artist.isBlank()) return null
        val referer = "https://music.163.com"
        val body = httpGet(
            "https://music.163.com/api/search/get/web?csrf_token=&hlpretag=&hlposttag=&s=${enc("$title $artist")}&type=1&offset=0&total=true&limit=6",
            referer
        ) ?: return null
        val songs = JSONObject(body).optJSONObject("result")?.optJSONArray("songs") ?: return null
        for (i in 0 until songs.length()) {
            val o = songs.optJSONObject(i) ?: continue
            val id = o.optLong("id")
            if (id == 0L) continue
            val artistsArr = o.optJSONArray("artists")
            val singers = StringBuilder()
            if (artistsArr != null) {
                for (j in 0 until artistsArr.length()) {
                    singers.append(artistsArr.optJSONObject(j)?.optString("name").orEmpty()).append(' ')
                }
            }
            if (!titleMatches(title, o.optString("name"))) continue
            if (!artistMatches(artist, singers.toString())) continue
            if (!durationMatches(durationSec, o.optLong("duration", 0L) / 1000.0)) continue

            val lyricBody = httpGet("https://music.163.com/api/song/lyric?id=$id&lv=1&kv=1&tv=-1", referer) ?: continue
            val lrc = JSONObject(lyricBody).optJSONObject("lrc")?.optString("lyric").orEmpty().trim()
            if (lrc.isBlank()) continue
            return LyricsResult(lrc, "", hasTimestamps(lrc), "NetEase", o.optLong("duration", 0L) / 1000.0)
        }
        return null
    }

    private fun qqMusic(title: String, artist: String, durationSec: Long): LyricsResult? {
        if (artist.isBlank()) return null
        val referer = "https://y.qq.com/"
        val body = httpGet(
            "https://c.y.qq.com/soso/fcgi-bin/client_search_cp?w=${enc("$title $artist")}&format=json&p=1&n=6&cr=1&new_json=1",
            referer
        ) ?: return null
        val list = JSONObject(body).optJSONObject("data")?.optJSONObject("song")?.optJSONArray("list") ?: return null
        for (i in 0 until list.length()) {
            val o = list.optJSONObject(i) ?: continue
            val mid = o.optString("mid").ifBlank { o.optString("songmid") }
            val name = o.optString("title").ifBlank { o.optString("songname") }
            val interval = o.optDouble("interval", 0.0)
            if (mid.isBlank()) continue

            val singers = StringBuilder()
            val singerArr = o.optJSONArray("singer")
            if (singerArr != null) {
                for (j in 0 until singerArr.length()) {
                    singers.append(singerArr.optJSONObject(j)?.optString("name").orEmpty()).append(' ')
                }
            }
            if (!titleMatches(title, name)) continue
            if (!artistMatches(artist, singers.toString())) continue
            if (!durationMatches(durationSec, interval)) continue

            val lyricBody = httpGet(
                "https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg?songmid=$mid&format=json&nobase64=1",
                referer
            ) ?: continue
            val raw = JSONObject(lyricBody).optString("lyric")
            if (raw.isBlank()) continue
            // Respons memakai entitas HTML (&#58; dst), perlu didekode
            val text = Html.fromHtml(raw, Html.FROM_HTML_MODE_LEGACY).toString().trim()
            if (text.isBlank()) continue
            return LyricsResult(text, "", hasTimestamps(text), "QQ Music", interval)
        }
        return null
    }

    private suspend fun youtubeLyrics(videoId: String): LyricsResult? {
        if (videoId.isBlank()) return null
        val fetcher = ytMusicLyrics ?: return null
        val text = fetcher(videoId)?.trim().orEmpty()
        return if (text.isNotBlank()) LyricsResult(text, "", false, "YouTube Music") else null
    }

    private fun lyricsOvh(title: String, artist: String): LyricsResult? {
        if (artist.isBlank()) return null
        val body = httpGet("https://api.lyrics.ovh/v1/${enc(artist)}/${enc(title)}") ?: return null
        val text = JSONObject(body).optString("lyrics")
        return if (text.isNotBlank()) LyricsResult(text.trim(), "", false, "lyrics.ovh") else null
    }

    // ------------------------------------------------------------ pemeriksaan kecocokan

    private val placeholderArtists = setOf(
        "song", "lagu", "video", "artist", "artis", "single", "ep", "album", "music", "musik", "unknown", "unknown artist"
    )

    private val timestampRegex = Regex("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?]")

    private fun hasTimestamps(text: String): Boolean = timestampRegex.containsMatchIn(text)

    /** Lirik tersinkron tidak boleh jauh lebih panjang dari lagunya. */
    private fun plausible(lrc: String, durationSec: Long): Boolean {
        if (durationSec <= 0) return true
        val last = timestampRegex.findAll(lrc).lastOrNull() ?: return true
        val min = last.groupValues[1].toLongOrNull() ?: return true
        val sec = last.groupValues[2].toLongOrNull() ?: return true
        val lastSec = min * 60 + sec
        // Hanya tolak yang jelas bukan rekaman ini: berakhir jauh setelah lagu selesai atau jauh sebelum selesai
        return lastSec <= durationSec + 45 && lastSec >= durationSec * 0.25
    }

    private fun norm(s: String): String {
        val decomposed = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
        return decomposed.replace(Regex("\\p{Mn}+"), "").replace(Regex("[^\\p{L}\\p{N}]+"), "")
    }

    private fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            val tmp = prev
            prev = cur
            cur = tmp
        }
        return prev[b.length]
    }

    private fun similarity(a: String, b: String): Double {
        val na = norm(a)
        val nb = norm(b)
        if (na.isEmpty() || nb.isEmpty()) return 0.0
        if (na == nb) return 1.0
        return 1.0 - levenshtein(na, nb).toDouble() / maxOf(na.length, nb.length)
    }

    private fun titleMatches(wanted: String, candidate: String): Boolean {
        val w = norm(cleanTitle(wanted))
        val c = norm(cleanTitle(candidate))
        if (w.isEmpty() || c.isEmpty()) return false
        if (w == c) return true
        if (similarity(w, c) >= 0.75) return true
        val shorter = if (w.length < c.length) w else c
        val longer = if (w.length < c.length) c else w
        return shorter.length >= 5 && longer.contains(shorter) && shorter.length.toDouble() / longer.length >= 0.6
    }

    private fun artistMatches(wanted: String, candidate: String): Boolean {
        if (wanted.isBlank() || candidate.isBlank()) return true
        val w = norm(wanted)
        val c = norm(candidate)
        if (w.isEmpty() || c.isEmpty()) return true
        if (c.contains(w) || w.contains(c)) return true
        val parts = wanted.split(",", "&", ";", "/", " x ", " feat", " ft.")
            .map { norm(it) }
            .filter { it.length >= 3 }
        if (parts.any { c.contains(it) }) return true
        return similarity(w, c) >= 0.7
    }

    /**
     * Durasi hanya dipakai untuk menolak lagu yang jelas berbeda (selisih sangat besar) dan untuk
     * mengurutkan kandidat. Selisih kecil di ujung lagu tidak menggeser waktu lirik.
     */
    private fun durationMatches(wantedSec: Long, candidateSec: Double, tolerance: Double = 20.0): Boolean {
        if (wantedSec <= 0 || candidateSec <= 0.0) return true
        return abs(candidateSec - wantedSec) <= tolerance
    }

    // ------------------------------------------------------------ util

    private fun httpGet(url: String, referer: String? = null): String? {
        return try {
            val builder = Request.Builder().url(url)
            if (referer != null) {
                builder.header("User-Agent", browserUa).header("Referer", referer)
            } else {
                builder.header("User-Agent", ua)
            }
            client.newCall(builder.build()).execute().use { r ->
                if (r.isSuccessful) r.body?.string() else null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private val junkInTitle = Regex(
        "\\s*[\\(\\[][^\\)\\]]*(official|video|audio|lyric|lirik|mv|m/v|hd|4k|visualizer|live|remaster|clip)[^\\)\\]]*[\\)\\]]",
        RegexOption.IGNORE_CASE
    )

    fun cleanTitle(raw: String): String {
        var t = raw.replace(junkInTitle, "")
        t = t.replace(Regex("\\s*[\\(\\[]?\\s*(feat\\.?|ft\\.?|featuring)\\s[^\\)\\]]*[\\)\\]]?", RegexOption.IGNORE_CASE), "")
        t = t.replace(Regex("\\s*[-|]\\s*(official|lyrics?|lirik|video|audio).*$", RegexOption.IGNORE_CASE), "")
        return t.trim().ifBlank { raw.trim() }
    }

    fun cleanArtist(raw: String): String {
        var a = raw.replace(Regex("\\s*-\\s*Topic$", RegexOption.IGNORE_CASE), "")
        a = a.replace(Regex("VEVO$", RegexOption.IGNORE_CASE), "")
        a = a.split(",", "&", " x ", " feat", " ft.").first()
        return a.trim().ifBlank { raw.trim() }
    }

    private companion object {
        const val TAG = "UnMusicLyrics"
    }
}

/**
 * Durasi lagu dalam detik untuk pencocokan lirik, atau 0 bila tidak diketahui.
 * Nilai 180000 dan 210000 ms adalah angka bawaan lama parser (bukan durasi asli), jadi dianggap tidak diketahui.
 */
fun lyricDurationSec(durationMs: Long): Long {
    if (durationMs <= 0L || durationMs == 180_000L || durationMs == 210_000L) return 0L
    return durationMs / 1000L
}

/**
 * True bila teks tampak seperti halaman HTML (mis. halaman error atau beranda sebuah API), bukan lirik.
 * Lirik tidak boleh menampilkan kode HTML.
 */
fun lyricsLookLikeHtml(text: String): Boolean {
    val t = text.trim().lowercase()
    if (t.isEmpty()) return false
    if (t.startsWith("<!doctype") || t.startsWith("<html") || t.startsWith("<?xml")) return true
    if (t.contains("<head") || t.contains("<body") || t.contains("<script") || t.contains("</div>") || t.contains("</html>")) return true
    val tagCount = Regex("</?[a-z][a-z0-9]*(\\s[^>]*)?/?>").findAll(t).count()
    return tagCount >= 3
}
