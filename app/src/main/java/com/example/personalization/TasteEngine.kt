package com.example.personalization

import android.content.Context
import com.example.core.data.model.TrackEntity
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale
import kotlin.math.ln
import kotlin.math.pow
import kotlin.random.Random

/**
 * Profil selera lokal (tanpa akun, tanpa server). Setiap pemutaran, skip, dan like
 * mengubah bobot artis, bahasa, mood, dan pola waktu. Bobot meluruh seiring waktu.
 */
class TasteEngine(context: Context) {

    private class Entry(var score: Double, var ts: Long)

    private val prefs = context.getSharedPreferences("taste_v2", Context.MODE_PRIVATE)
    private val lock = Any()
    private val artists = load("artists")
    private val langs = load("langs")
    private val moods = load("moods")
    private val moodTime = load("moodTime")

    var enabled: Boolean
        get() = prefs.getBoolean("enabled", true)
        set(value) { prefs.edit().putBoolean("enabled", value).apply() }

    private fun load(key: String): MutableMap<String, Entry> {
        val map = mutableMapOf<String, Entry>()
        try {
            val obj = JSONObject(prefs.getString(key, "{}") ?: "{}")
            for (k in obj.keys()) {
                val arr = obj.getJSONArray(k)
                map[k] = Entry(arr.getDouble(0), arr.getLong(1))
            }
        } catch (_: Exception) {
        }
        return map
    }

    private fun save() {
        fun dump(m: Map<String, Entry>): String {
            val obj = JSONObject()
            for ((k, e) in m) obj.put(k, JSONArray().put(e.score).put(e.ts))
            return obj.toString()
        }
        prefs.edit()
            .putString("artists", dump(artists))
            .putString("langs", dump(langs))
            .putString("moods", dump(moods))
            .putString("moodTime", dump(moodTime))
            .apply()
    }

    private fun decayed(e: Entry?, now: Long): Double {
        if (e == null) return 0.0
        val days = (now - e.ts).coerceAtLeast(0L) / 86_400_000.0
        return e.score * 0.5.pow(days / 21.0)
    }

    private fun bump(map: MutableMap<String, Entry>, key: String, delta: Double, now: Long) {
        if (key.isBlank()) return
        val current = decayed(map[key], now)
        map[key] = Entry((current + delta).coerceIn(-6.0, 40.0), now)
    }

    private fun bucket(now: Long): String {
        val cal = Calendar.getInstance().apply { timeInMillis = now }
        return when (cal.get(Calendar.HOUR_OF_DAY)) {
            in 5..10 -> "pagi"
            in 11..16 -> "siang"
            in 17..21 -> "sore"
            else -> "malam"
        }
    }

    private fun artistKey(t: TrackEntity) = t.artist.trim().lowercase()

    fun hasData(): Boolean = synchronized(lock) { artists.isNotEmpty() }

    /** Catat hasil mendengarkan satu lagu. */
    fun record(track: TrackEntity, playedMs: Long, completed: Boolean, skippedEarly: Boolean, liked: Boolean = false) {
        if (!enabled) return
        val now = System.currentTimeMillis()
        val delta = when {
            liked -> 2.5
            completed -> 1.0
            skippedEarly -> -1.2
            playedMs >= 45_000L -> 0.5
            else -> 0.0
        }
        if (delta == 0.0) return
        val lang = TrackTags.language(track)
        val mood = TrackTags.mood(track)
        synchronized(lock) {
            bump(artists, artistKey(track), delta, now)
            bump(langs, lang, delta * 0.5, now)
            bump(moods, mood, delta * 0.5, now)
            if (mood.isNotBlank()) bump(moodTime, "${bucket(now)}|$mood", delta * 0.6, now)
            save()
        }
    }

    fun score(track: TrackEntity, now: Long = System.currentTimeMillis()): Double {
        if (!enabled) return 0.0
        val lang = TrackTags.language(track)
        val mood = TrackTags.mood(track)
        var s = 0.0
        synchronized(lock) {
            s += decayed(artists[artistKey(track)], now)
            s += decayed(langs[lang], now) * 0.6
            s += decayed(moods[mood], now) * 0.4
            if (mood.isNotBlank()) s += decayed(moodTime["${bucket(now)}|$mood"], now) * 0.5
        }
        if (track.isLiked) s += 1.5
        s += 0.15 * ln(1.0 + track.playCount)
        s -= 0.5 * track.skipCount
        if (track.lastPlayedAt > 0 && now - track.lastPlayedAt < 2 * 3_600_000L) s -= 1.0
        return s
    }

    fun topArtists(n: Int): List<String> {
        val now = System.currentTimeMillis()
        return synchronized(lock) {
            artists.entries.map { it.key to decayed(it.value, now) }
                .filter { it.second > 0.3 }
                .sortedByDescending { it.second }
                .take(n).map { it.first }
        }
    }

    fun dominantLanguage(): String {
        val now = System.currentTimeMillis()
        val learned = synchronized(lock) {
            langs.entries.map { it.key to decayed(it.value, now) }.maxByOrNull { it.second }
        }
        if (learned != null && learned.second > 0.8) return learned.first
        return deviceLanguage()
    }

    private fun deviceLanguage(): String {
        val code = Locale.getDefault().language
        return if (code == "in") "id" else code
    }

    /**
     * Susunan Quick Picks: ~50% akrab, ~30% mirip, ~20% baru.
     * Pengguna baru: utamakan bahasa/wilayah perangkat.
     */
    fun quickPicks(all: List<TrackEntity>, count: Int = 12): List<TrackEntity> {
        if (all.isEmpty() || !enabled) return all
        val now = System.currentTimeMillis()
        val pool = all.filter { it.unplayableUntil <= now }.ifEmpty { return all }
        val deviceLang = dominantLanguage()

        if (!hasData()) {
            val (match, rest) = pool.partition { TrackTags.language(it) == deviceLang }
            return match + rest
        }

        val scored = pool.map { it to (score(it, now) + if (TrackTags.language(it) == deviceLang) 0.4 else 0.0) }
            .sortedByDescending { it.second }
        val familiar = scored.filter { it.first.playCount > 0 || it.first.isLiked }.map { it.first }
        val unheard = scored.filter { it.first.playCount == 0 && !it.first.isLiked }.map { it.first }

        val daySeed = now / 86_400_000L
        val rnd = Random(daySeed)
        val similar = unheard.take(count * 3).filter { score(it, now) > 0.2 }.take(count)
        val fresh = unheard.filter { TrackTags.language(it) == deviceLang }.shuffled(rnd).take(count)

        val nFamiliar = (count * 0.5).toInt().coerceAtLeast(1)
        val nSimilar = (count * 0.3).toInt()
        val result = LinkedHashSet<TrackEntity>()
        familiar.take(nFamiliar).forEach { result.add(it) }
        similar.take(nSimilar).forEach { result.add(it) }
        fresh.take(count - result.size).forEach { result.add(it) }
        scored.forEach { if (result.size < count) result.add(it.first) }

        val head = result.toList()
        val tail = scored.map { it.first }.filter { it !in result }
        return head + tail
    }

    fun reset() {
        synchronized(lock) {
            artists.clear(); langs.clear(); moods.clear(); moodTime.clear()
            save()
        }
    }
}
