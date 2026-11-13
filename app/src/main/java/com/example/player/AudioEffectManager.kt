package com.example.player

import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.Virtualizer

enum class EqPreset(val displayName: String, val bandGains: IntArray) {
    FLAT("Flat", intArrayOf(0, 0, 0, 0, 0)),
    BASS_BOOST("Bass Heavy", intArrayOf(600, 400, 100, 0, -100)),
    ROCK("Rock", intArrayOf(400, 200, -100, 200, 500)),
    POP("Pop", intArrayOf(-100, 200, 500, 200, -100)),
    VOCAL("Vocal Boost", intArrayOf(-200, 0, 600, 400, 100)),
    CLASSICAL("Classical", intArrayOf(400, 300, 0, 200, 300))
}

class AudioEffectManager {
    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private var virtualizer: Virtualizer? = null

    fun attachAudioSession(audioSessionId: Int) {
        if (audioSessionId <= 0) return
        release()
        try {
            equalizer = Equalizer(0, audioSessionId).apply {
                enabled = true
            }
            bassBoost = BassBoost(0, audioSessionId).apply {
                enabled = true
            }
            virtualizer = Virtualizer(0, audioSessionId).apply {
                enabled = true
            }
        } catch (_: Exception) {}
    }

    fun setBassBoostStrength(strength: Int) { // 0 to 1000
        try {
            bassBoost?.let {
                if (it.strengthSupported) {
                    it.setStrength(strength.toShort())
                }
            }
        } catch (_: Exception) {}
    }

    fun setVirtualizerStrength(strength: Int) { // 0 to 1000
        try {
            virtualizer?.let {
                if (it.strengthSupported) {
                    it.setStrength(strength.toShort())
                }
            }
        } catch (_: Exception) {}
    }

    fun applyPreset(preset: EqPreset) {
        try {
            equalizer?.let { eq ->
                val numBands = eq.numberOfBands.toInt().coerceAtMost(5)
                for (i in 0 until numBands) {
                    if (i < preset.bandGains.size) {
                        val gain = preset.bandGains[i].toShort()
                        eq.setBandLevel(i.toShort(), gain)
                    }
                }
            }
        } catch (_: Exception) {}
    }

    fun release() {
        try {
            equalizer?.release()
            bassBoost?.release()
            virtualizer?.release()
        } catch (_: Exception) {}
        equalizer = null
        bassBoost = null
        virtualizer = null
    }
}
