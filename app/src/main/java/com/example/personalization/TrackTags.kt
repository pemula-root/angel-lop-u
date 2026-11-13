package com.example.personalization

import com.example.core.data.model.TrackEntity

/** Deteksi bahasa dan mood ringan di perangkat, dari judul dan nama artis. */
object TrackTags {

    private val idWords = setOf(
        "yang", "dan", "aku", "kamu", "kau", "cinta", "hati", "rindu", "untuk", "tak", "tidak", "dia",
        "sayang", "kita", "mau", "pergi", "pulang", "malam", "hujan", "bukan", "jangan", "tapi", "karena",
        "dengan", "sama", "ada", "itu", "ini", "mimpi", "cerita", "senja", "kenangan", "luka", "bahagia",
        "sedih", "terakhir", "selamanya", "di", "ke", "dari", "pada", "saat", "waktu", "hari", "kasih",
        "bersamamu", "tanpa", "lagi", "sudah", "belum", "akan", "masih", "pernah", "selalu", "sendiri",
        "jalan", "rumah", "bintang", "bulan", "langit", "laut", "pelangi", "doa", "ibu", "ayah", "sahabat",
        "ora", "kowe", "ati", "tresno", "loro", "wong", "sliramu", "kangen", "ambyar", "dudu"
    )

    private val enWords = setOf(
        "the", "you", "love", "me", "my", "your", "to", "of", "and", "in", "on", "we", "it", "is", "baby",
        "night", "heart", "with", "for", "that", "this", "all", "be", "not", "just", "like", "from", "when",
        "never", "again", "feel", "know", "want", "time", "life", "away", "down", "home", "don't", "i'm"
    )

    private val idArtists = listOf(
        "tulus", "raisa", "noah", "dewa 19", "sheila on 7", "peterpan", "ungu", "hindia", "nadin amizah",
        "juicy luicy", "pamungkas", "mahalini", "rizky febian", "isyana", "armada", "kerispatih", "slank",
        "ari lasso", "afgan", "tiara andini", "lyodra", "ziva magnolya", "andmesh", "yovie", "rossa",
        "bunga citra", "judika", "fiersa besari", "feby putri", "denny caknan", "guyon waton", "happy asmara",
        "ndarboy", "via vallen", "nella kharisma", "anji", "kunto aji", "sal priadi", "bernadya", "tompi",
        "glenn fredly", "cakra khan", "gac", "geisha", "d'masiv", "wali", "letto", "nidji", "repvblik",
        "virgoun", "ghea indrawari", "keisya levronka", "adera", "vierra", "ebiet", "iwan fals", "chrisye",
        "padi", "dadali", "kangen band", "st12", "hivi", "maudy ayunda", "agnez mo", "rony parulian",
        "arsy widianto", "nabila maharani", "shanna shannon", "mawar de jongh", "tri suaka", "lesti", "fildan"
    )

    private val sadWords = setOf(
        "sad", "cry", "tears", "lonely", "alone", "heartbreak", "goodbye", "miss", "pain", "broken", "hurt",
        "sedih", "patah", "tangis", "rindu", "kehilangan", "luka", "kenangan", "sendiri", "pergi", "hancur",
        "perih", "lara", "sepi", "menangis", "tinggalkan", "kecewa", "maaf", "terluka", "galau", "lelah",
        "ambyar", "kangen", "ikhlas", "mengikhlaskan", "tanpamu", "kehilanganmu", "perpisahan", "air", "mata"
    )
    private val partyWords = setOf("party", "dance", "club", "remix", "dj", "goyang", "pesta", "koplo", "dugem", "bass")
    private val chillWords = setOf("chill", "acoustic", "lofi", "lo-fi", "calm", "sleep", "santai", "tenang", "piano", "slow", "relax", "cafe")
    private val energeticWords = setOf("workout", "gym", "energy", "power", "semangat", "rock", "metal", "hype", "run", "fire", "bangkit")
    private val romanticWords = setOf("love", "cinta", "sayang", "romantic", "kasih", "valentine", "jatuh", "bersamamu", "selamanya", "kekasih", "pacar")

    fun language(title: String, artist: String): String {
        val text = "$title $artist"
        var hangul = 0; var kana = 0; var han = 0; var arabic = 0; var deva = 0; var thai = 0; var cyr = 0
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            when (Character.UnicodeScript.of(cp)) {
                Character.UnicodeScript.HANGUL -> hangul++
                Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA -> kana++
                Character.UnicodeScript.HAN -> han++
                Character.UnicodeScript.ARABIC -> arabic++
                Character.UnicodeScript.DEVANAGARI -> deva++
                Character.UnicodeScript.THAI -> thai++
                Character.UnicodeScript.CYRILLIC -> cyr++
                else -> {}
            }
            i += Character.charCount(cp)
        }
        if (hangul > 0) return "ko"
        if (kana > 0) return "ja"
        if (han > 0) return "zh"
        if (deva > 0) return "hi"
        if (arabic > 0) return "ar"
        if (thai > 0) return "th"
        if (cyr > 0) return "ru"

        val artistLower = artist.lowercase()
        if (idArtists.any { artistLower.contains(it) }) return "id"
        val tokens = text.lowercase().split(Regex("[^a-z']+")).filter { it.isNotEmpty() }
        val idHits = tokens.count { it in idWords }
        val enHits = tokens.count { it in enWords }
        return when {
            idHits >= 1 && idHits >= enHits -> "id"
            enHits >= 1 && idHits == 0 -> "en"
            else -> ""
        }
    }

    /** Seperti [language] tetapi hanya bila yakin (aksara khusus, artis dikenal, atau banyak kata cocok). */
    fun languageStrict(title: String, artist: String): String {
        val l = language(title, artist)
        if (l.isEmpty()) return ""
        if (l != "id" && l != "en") return l
        val artistLower = artist.lowercase()
        if (l == "id" && idArtists.any { artistLower.contains(it) }) return "id"
        val tokens = "$title $artist".lowercase().split(Regex("[^a-z']+")).filter { it.isNotEmpty() }
        val idHits = tokens.count { it in idWords }
        val enHits = tokens.count { it in enWords }
        return when {
            l == "id" && idHits >= 2 -> "id"
            l == "en" && enHits >= 3 && idHits == 0 -> "en"
            else -> ""
        }
    }

    fun mood(title: String, album: String = ""): String {
        val tokens = "$title $album".lowercase().split(Regex("[^a-z0-9-]+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return ""
        val scores = linkedMapOf(
            "sad" to tokens.count { it in sadWords },
            "romantic" to tokens.count { it in romanticWords },
            "chill" to tokens.count { it in chillWords },
            "energetic" to tokens.count { it in energeticWords },
            "party" to tokens.count { it in partyWords }
        )
        val best = scores.maxByOrNull { it.value } ?: return ""
        return if (best.value > 0) best.key else ""
    }

    fun language(t: TrackEntity): String = t.language.ifBlank { language(t.title, t.artist) }

    fun mood(t: TrackEntity): String = t.mood.ifBlank { mood(t.title, t.album) }

    /** True bila dua mood saling bertentangan (mis. sedih vs pesta). */
    fun conflict(a: String, b: String): Boolean {
        if (a.isBlank() || b.isBlank() || a == b) return false
        val calm = setOf("sad", "chill")
        val loud = setOf("party", "energetic")
        return (a in calm && b in loud) || (b in calm && a in loud)
    }

    fun chipMatches(t: TrackEntity, chip: String): Boolean {
        val m = mood(t)
        return when (chip) {
            "Sad" -> m == "sad"
            "Relax", "Focus" -> m == "chill"
            "Feel good" -> m == "romantic" || m == "party"
            "Energize", "Workout" -> m == "energetic" || m == "party"
            "Party" -> m == "party" || m == "energetic"
            else -> true
        }
    }
}
