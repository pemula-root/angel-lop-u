package com.example.core.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "unmusic_prefs")

enum class ThemeStyle {
    DYNAMIC,
    DARK,
    AMOLED,
    LIGHT
}

class PreferencesRepository(private val context: Context) {
    private val dataStore = context.dataStore

    companion object {
        val KEY_THEME_STYLE = stringPreferencesKey("theme_style")
        val KEY_ACCENT_COLOR = intPreferencesKey("accent_color")
        val KEY_LYRICS_FONT_SIZE = floatPreferencesKey("lyrics_font_size")
        val KEY_LYRICS_ALIGN_CENTER = booleanPreferencesKey("lyrics_align_center")
        val KEY_LYRICS_OFFSET_MS = longPreferencesKey("lyrics_offset_ms")
        val KEY_OFFLINE_ONLY = booleanPreferencesKey("offline_only")
        val KEY_NORMALIZE_VOLUME = booleanPreferencesKey("normalize_volume")
        val KEY_CROSSFADE_SEC = intPreferencesKey("crossfade_sec")
        val KEY_SLEEP_TIMER_MIN = intPreferencesKey("sleep_timer_min")
        val KEY_EQ_PRESET = stringPreferencesKey("eq_preset")
        val KEY_BASS_BOOST = intPreferencesKey("bass_boost")
        val KEY_VIRTUALIZER = intPreferencesKey("virtualizer")
        val KEY_LAST_TRACK_ID = stringPreferencesKey("last_track_id")
        val KEY_LAST_POSITION_MS = longPreferencesKey("last_position_ms")
    }

    val themeStyleFlow: Flow<ThemeStyle> = dataStore.data.map { prefs ->
        val name = prefs[KEY_THEME_STYLE] ?: ThemeStyle.DYNAMIC.name
        runCatching { ThemeStyle.valueOf(name) }.getOrDefault(ThemeStyle.DYNAMIC)
    }

    val accentColorFlow: Flow<Int> = dataStore.data.map { prefs ->
        prefs[KEY_ACCENT_COLOR] ?: 0xFF00E5FF.toInt()
    }

    val lyricsFontSizeFlow: Flow<Float> = dataStore.data.map { prefs ->
        prefs[KEY_LYRICS_FONT_SIZE] ?: 22f
    }

    val lyricsAlignCenterFlow: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_LYRICS_ALIGN_CENTER] ?: true
    }

    val lyricsOffsetMsFlow: Flow<Long> = dataStore.data.map { prefs ->
        prefs[KEY_LYRICS_OFFSET_MS] ?: 0L
    }

    val offlineOnlyFlow: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_OFFLINE_ONLY] ?: false
    }

    val normalizeVolumeFlow: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_NORMALIZE_VOLUME] ?: false
    }

    val crossfadeSecFlow: Flow<Int> = dataStore.data.map { prefs ->
        prefs[KEY_CROSSFADE_SEC] ?: 0
    }

    val lastTrackIdFlow: Flow<String?> = dataStore.data.map { prefs ->
        prefs[KEY_LAST_TRACK_ID]
    }

    val lastPositionMsFlow: Flow<Long> = dataStore.data.map { prefs ->
        prefs[KEY_LAST_POSITION_MS] ?: 0L
    }

    val bassBoostFlow: Flow<Int> = dataStore.data.map { prefs ->
        prefs[KEY_BASS_BOOST] ?: 0
    }

    val virtualizerFlow: Flow<Int> = dataStore.data.map { prefs ->
        prefs[KEY_VIRTUALIZER] ?: 0
    }

    suspend fun setThemeStyle(style: ThemeStyle) {
        dataStore.edit { it[KEY_THEME_STYLE] = style.name }
    }

    suspend fun setAccentColor(color: Int) {
        dataStore.edit { it[KEY_ACCENT_COLOR] = color }
    }

    suspend fun setLyricsFontSize(size: Float) {
        dataStore.edit { it[KEY_LYRICS_FONT_SIZE] = size }
    }

    suspend fun setLyricsAlignCenter(center: Boolean) {
        dataStore.edit { it[KEY_LYRICS_ALIGN_CENTER] = center }
    }

    suspend fun setLyricsOffsetMs(offset: Long) {
        dataStore.edit { it[KEY_LYRICS_OFFSET_MS] = offset }
    }

    suspend fun setOfflineOnly(offline: Boolean) {
        dataStore.edit { it[KEY_OFFLINE_ONLY] = offline }
    }

    suspend fun setNormalizeVolume(normalize: Boolean) {
        dataStore.edit { it[KEY_NORMALIZE_VOLUME] = normalize }
    }

    suspend fun setCrossfadeSec(sec: Int) {
        dataStore.edit { it[KEY_CROSSFADE_SEC] = sec }
    }

    suspend fun saveLastSession(trackId: String, positionMs: Long) {
        dataStore.edit {
            it[KEY_LAST_TRACK_ID] = trackId
            it[KEY_LAST_POSITION_MS] = positionMs
        }
    }

    suspend fun setBassBoost(level: Int) {
        dataStore.edit { it[KEY_BASS_BOOST] = level }
    }

    suspend fun setVirtualizer(level: Int) {
        dataStore.edit { it[KEY_VIRTUALIZER] = level }
    }
}
