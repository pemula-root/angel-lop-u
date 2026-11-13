package com.example.personalization

import com.example.core.data.model.TrackEntity

/** Memilih lagu lanjutan radio yang nyambung dengan lagu acuan (bahasa, mood, artis). */
object RadioSelector {

    private val versionMarkers = listOf(
        "live", "remix", "cover", "slowed", "sped up", "speed up", "instrumental", "karaoke",
        "8d", "nightcore", "reverb", "acoustic", "mashup", "medley"
    )

    private fun markers(title: String): Set<String> {
        val t = title.lowercase()
        return versionMarkers.filter { Regex("\\b" + Regex.escape(it) + "\\b").containsMatchIn(t) }.toSet()
    }

    private fun normTitle(title: String): String =
        title.lowercase().replace(Regex("\\([^)]*\\)|\\[[^\\]]*\\]"), "").replace(Regex("[^\\p{L}\\p{N}]+"), "")

    private val badTitleWords = listOf(
        "full album", "1 hour", "2 hour", "3 hour", "1 jam", "2 jam", "3 jam", "livestream", "live stream", "compilation", "non stop", "nonstop"
    )

    fun select(
        seed: TrackEntity,
        candidates: List<TrackEntity>,
        excludeIds: Set<String>,
        taste: TasteEngine?,
        want: Int = 12
    ): List<TrackEntity> {
        val seedLang = TrackTags.language(seed)
        val seedMood = TrackTags.mood(seed)
        val seedArtist = seed.artist.trim().lowercase()
        val now = System.currentTimeMillis()

        val seedMarkers = markers(seed.title)
        val seedNorm = normTitle(seed.title)
        val seedStrict = TrackTags.languageStrict(seed.title, seed.artist)

        val usable = candidates.filter { c ->
            c.id !in excludeIds &&
                markers(c.title).all { it in seedMarkers } &&
                normTitle(c.title) != seedNorm &&
                c.unplayableUntil <= now &&
                (c.durationMs == 0L || c.durationMs <= 12 * 60_000L) &&
                badTitleWords.none { c.title.lowercase().contains(it) }
        }
        if (usable.isEmpty()) return emptyList()

        val n = usable.size.toDouble()
        val base = HashMap<String, Double>()
        usable.forEachIndexed { idx, c ->
            var s = 2.0 * (n - idx) / n
            val lang = TrackTags.language(c)
            val mood = TrackTags.mood(c)
            val candStrict = TrackTags.languageStrict(c.title, c.artist)
            if (seedStrict.isNotBlank() && candStrict.isNotBlank()) {
                s += if (seedStrict == candStrict) 1.0 else -4.0
            } else if (seedLang.isNotBlank() && lang.isNotBlank() && seedLang != lang) {
                s -= 0.6
            }
            if (TrackTags.conflict(seedMood, mood)) s -= 3.0
            else if (seedMood.isNotBlank() && seedMood == mood) s += 1.0
            if (c.artist.trim().lowercase() == seedArtist) s += 0.3
            if (taste != null) s += taste.score(c, now).coerceIn(-3.0, 3.0) * 0.5
            base[c.id] = s
        }

        val picked = mutableListOf<TrackEntity>()
        val artistCount = HashMap<String, Int>()
        val remaining = usable.toMutableList()
        var lastArtist = seedArtist
        var consecutive = 0

        while (picked.size < want && remaining.isNotEmpty()) {
            var best: TrackEntity? = null
            var bestScore = Double.NEGATIVE_INFINITY
            for (c in remaining) {
                val a = c.artist.trim().lowercase()
                var s = base[c.id] ?: 0.0
                s -= 1.5 * (artistCount[a] ?: 0)
                if (a == lastArtist && consecutive >= 2) s -= 5.0
                if (s > bestScore) { bestScore = s; best = c }
            }
            val chosen = best ?: break
            val chosenNorm = normTitle(chosen.title)
            if (picked.any { normTitle(it.title) == chosenNorm }) {
                remaining.remove(chosen)
                continue
            }
            if (bestScore < -1.5 && picked.size >= 5) break
            picked.add(chosen)
            remaining.remove(chosen)
            val a = chosen.artist.trim().lowercase()
            artistCount[a] = (artistCount[a] ?: 0) + 1
            if (a == lastArtist) consecutive++ else { lastArtist = a; consecutive = 1 }
        }
        return picked
    }
}
