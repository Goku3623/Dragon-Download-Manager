package com.example.data.audio

import android.content.Context
import android.media.audiofx.BassBoost
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.Virtualizer
import android.os.Build
import kotlin.math.roundToInt

enum class AudioQualityPreset(
    val shortLabel: String,
    val displayName: String,
    val bandGainsDb: FloatArray, // 10-band center frequencies: 31, 62, 125, 250, 500, 1k, 2k, 4k, 8k, 16k Hz
    val bassBoostStrength: Short, // 0..1000 (fallback path)
    val virtualizerStrength: Short, // 0..1000
    val loudnessGainMb: Int, // millibels (subtle makeup gain)
    val preGainDb: Float,
    val limiterThresholdDb: Float
) {
    HI_FI(
        shortLabel = "Hi-Fi",
        displayName = "Hi-Fi Clarity",
        // Warm sub-bass, clean de-muddied low-mids, crisp presence & airy sparkle
        bandGainsDb = floatArrayOf(1.8f, 2.4f, 1.2f, -0.8f, 0.0f, 0.8f, 1.5f, 1.9f, 2.4f, 2.6f),
        bassBoostStrength = 260,
        virtualizerStrength = 220,
        loudnessGainMb = 180,
        preGainDb = 0.8f,
        limiterThresholdDb = -1.0f
    ),
    DEEP_BASS(
        shortLabel = "Bass+",
        displayName = "Deep Bass",
        // Tight sub-bass punch with crisp highs and anti-clipping limiter headroom
        bandGainsDb = floatArrayOf(4.6f, 4.8f, 3.0f, -0.6f, -0.4f, 0.5f, 1.2f, 1.6f, 2.0f, 1.8f),
        bassBoostStrength = 580,
        virtualizerStrength = 260,
        loudnessGainMb = 150,
        preGainDb = 0.4f,
        limiterThresholdDb = -1.2f
    ),
    VOCAL_CLARITY(
        shortLabel = "Vocal+",
        displayName = "Vocal Clarity",
        // Removes rumble and boosts midrange vocal articulation & air
        bandGainsDb = floatArrayOf(-2.0f, -1.2f, -0.4f, 0.4f, 1.4f, 2.4f, 2.8f, 2.2f, 1.4f, 0.8f),
        bassBoostStrength = 0,
        virtualizerStrength = 120,
        loudnessGainMb = 240,
        preGainDb = 1.0f,
        limiterThresholdDb = -1.0f
    ),
    PURE_DIRECT(
        shortLabel = "Pure",
        displayName = "Pure Direct",
        // Zero coloration, transparent studio pass-through
        bandGainsDb = floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f),
        bassBoostStrength = 0,
        virtualizerStrength = 0,
        loudnessGainMb = 0,
        preGainDb = 0f,
        limiterThresholdDb = -0.3f
    );

    fun next(): AudioQualityPreset {
        val all = entries
        return all[(ordinal + 1) % all.size]
    }
}

/**
 * Hardware-accelerated Android Audio DSP Engine attached directly to the MediaPlayer audio session.
 *
 * - On Android 9 (API 28)+, utilizes studio-grade 32-bit floating-point [DynamicsProcessing] with
 *   a 10-band precision parametric EQ, subtle multi-band clarity control, and true-peak anti-clipping
 *   limiter so boosted lows and highs remain distortion-free.
 * - Automatically falls back to [Equalizer] + [BassBoost] + [Virtualizer] + [LoudnessEnhancer] on
 *   older Android versions or OEM HALs that restrict DynamicsProcessing.
 */
internal class DragonAudioDspEngine {

    companion object {
        private const val PREFS_NAME = "dragon_audio_dsp_prefs"
        private const val KEY_PRESET = "audio_quality_preset"

        private val TEN_BAND_CUTOFF_HZ = floatArrayOf(
            45f, 90f, 180f, 355f, 710f, 1420f, 2840f, 5680f, 11360f, 20000f
        )
        private val TEN_BAND_CENTER_HZ = floatArrayOf(
            31f, 62f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f
        )

        fun loadSavedPreset(context: Context): AudioQualityPreset {
            val saved = runCatching {
                context.applicationContext
                    .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getString(KEY_PRESET, AudioQualityPreset.HI_FI.name)
            }.getOrNull()
            return AudioQualityPreset.entries.firstOrNull { it.name == saved } ?: AudioQualityPreset.HI_FI
        }

        fun savePreset(context: Context, preset: AudioQualityPreset) {
            runCatching {
                context.applicationContext
                    .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_PRESET, preset.name)
                    .apply()
            }
        }
    }

    private var attachedSessionId: Int = 0
    private var currentPreset: AudioQualityPreset = AudioQualityPreset.HI_FI

    private var dynamicsProcessing: DynamicsProcessing? = null
    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private var virtualizer: Virtualizer? = null
    private var loudnessEnhancer: LoudnessEnhancer? = null

    fun attachToSession(audioSessionId: Int, preset: AudioQualityPreset) {
        release()
        if (audioSessionId <= 0) return
        attachedSessionId = audioSessionId
        currentPreset = preset

        if (preset == AudioQualityPreset.PURE_DIRECT) {
            // Pure Direct bypasses DSP effects completely for bit-accurate playback
            return
        }

        var usedDynamics = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            usedDynamics = attachDynamicsProcessing(audioSessionId, preset)
        }

        if (!usedDynamics) {
            attachLegacyEffects(audioSessionId, preset)
        } else {
            // Add subtle stereo soundstage width alongside DynamicsProcessing if supported
            attachVirtualizerOnly(audioSessionId, preset)
        }
    }

    fun updatePreset(preset: AudioQualityPreset) {
        currentPreset = preset
        val sessionId = attachedSessionId
        if (sessionId <= 0) return

        if (preset == AudioQualityPreset.PURE_DIRECT) {
            releaseEffectsKeepingSession()
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && dynamicsProcessing != null) {
            val updated = applyPresetToDynamics(dynamicsProcessing!!, preset)
            updateVirtualizer(preset)
            if (updated) return
        }

        // Re-attach cleanly for new preset
        attachToSession(sessionId, preset)
    }

    fun release() {
        attachedSessionId = 0
        releaseEffectsKeepingSession()
    }

    private fun releaseEffectsKeepingSession() {
        runCatching { dynamicsProcessing?.enabled = false }
        runCatching { dynamicsProcessing?.release() }
        dynamicsProcessing = null

        runCatching { equalizer?.enabled = false }
        runCatching { equalizer?.release() }
        equalizer = null

        runCatching { bassBoost?.enabled = false }
        runCatching { bassBoost?.release() }
        bassBoost = null

        runCatching { virtualizer?.enabled = false }
        runCatching { virtualizer?.release() }
        virtualizer = null

        runCatching { loudnessEnhancer?.enabled = false }
        runCatching { loudnessEnhancer?.release() }
        loudnessEnhancer = null
    }

    private fun attachDynamicsProcessing(audioSessionId: Int, preset: AudioQualityPreset): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        return runCatching {
            val config = DynamicsProcessing.Config.Builder(
                DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                2, // Stereo channels
                true, TEN_BAND_CUTOFF_HZ.size, // Pre-EQ 10 bands
                true, 3, // 3-band MBC (Low / Mid / High) for clean dynamics
                false, 0, // Post-EQ not needed since Pre-EQ + MBC + Limiter is cleaner
                true // True-peak anti-clipping Limiter
            ).build()

            val dp = DynamicsProcessing(0, audioSessionId, config)
            if (!applyPresetToDynamics(dp, preset)) {
                runCatching { dp.release() }
                return false
            }
            dp.enabled = true
            dynamicsProcessing = dp
            true
        }.getOrDefault(false)
    }

    private fun applyPresetToDynamics(dp: DynamicsProcessing, preset: AudioQualityPreset): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        return runCatching {
            dp.setInputGainAllChannelsTo(preset.preGainDb)

            // 10-band precision EQ curve
            val eq = DynamicsProcessing.Eq(true, true, TEN_BAND_CUTOFF_HZ.size)
            for (i in TEN_BAND_CUTOFF_HZ.indices) {
                val band = DynamicsProcessing.EqBand(
                    true,
                    TEN_BAND_CUTOFF_HZ[i],
                    preset.bandGainsDb.getOrElse(i) { 0f }
                )
                eq.setBand(i, band)
            }
            dp.setPreEqAllChannelsTo(eq)

            // Gentle 3-band multi-band dynamics control for controlled bass & crisp micro-details
            val mbc = DynamicsProcessing.Mbc(true, true, 3)
            val lowBand = DynamicsProcessing.MbcBand(
                true,
                220f,
                10f, // attack ms
                90f, // release ms
                1.6f, // gentle compression ratio
                -18f, // threshold dB
                6f, // soft knee dB
                -85f, // noise gate
                1.0f, // expander ratio
                0.0f, // preGain
                if (preset == AudioQualityPreset.DEEP_BASS) 1.0f else 0.4f // subtle tight low postGain
            )
            val midBand = DynamicsProcessing.MbcBand(
                true,
                3200f,
                12f,
                80f,
                1.35f,
                -20f,
                6f,
                -85f,
                1.0f,
                0.0f,
                if (preset == AudioQualityPreset.VOCAL_CLARITY) 1.0f else 0.3f
            )
            val highBand = DynamicsProcessing.MbcBand(
                true,
                20000f,
                8f,
                65f,
                1.4f,
                -20f,
                6f,
                -85f,
                1.0f,
                0.0f,
                0.6f // airy high-end presence
            )
            mbc.setBand(0, lowBand)
            mbc.setBand(1, midBand)
            mbc.setBand(2, highBand)
            dp.setMbcAllChannelsTo(mbc)

            // Studio anti-clipping limiter ensures zero digital distortion on peaks
            val limiter = DynamicsProcessing.Limiter(
                true,
                true,
                0,
                1.0f, // fast 1ms attack
                60.0f, // transparent 60ms release
                10.0f, // brickwall-style ratio
                preset.limiterThresholdDb,
                0.0f
            )
            dp.setLimiterAllChannelsTo(limiter)
            true
        }.getOrDefault(false)
    }

    private fun attachVirtualizerOnly(audioSessionId: Int, preset: AudioQualityPreset) {
        if (preset.virtualizerStrength <= 0) return
        runCatching {
            val v = Virtualizer(0, audioSessionId)
            if (v.strengthSupported) {
                v.setStrength(preset.virtualizerStrength)
                v.enabled = true
                virtualizer = v
            } else {
                v.release()
            }
        }
    }

    private fun updateVirtualizer(preset: AudioQualityPreset) {
        val v = virtualizer
        if (preset.virtualizerStrength <= 0) {
            runCatching { v?.enabled = false }
            return
        }
        if (v != null) {
            runCatching {
                if (v.strengthSupported) {
                    v.setStrength(preset.virtualizerStrength)
                    v.enabled = true
                }
            }
        } else if (attachedSessionId > 0) {
            attachVirtualizerOnly(attachedSessionId, preset)
        }
    }

    private fun attachLegacyEffects(audioSessionId: Int, preset: AudioQualityPreset) {
        // 1. Hardware Equalizer interpolated from our 10-band curve
        runCatching {
            val eq = Equalizer(0, audioSessionId)
            val bands = eq.numberOfBands.toInt()
            val range = eq.bandLevelRange
            val minMb = range.getOrElse(0) { (-1500).toShort() }.toInt()
            val maxMb = range.getOrElse(1) { 1500.toShort() }.toInt()
            for (b in 0 until bands) {
                val centerHz = eq.getCenterFreq(b.toShort()) / 1000f
                val targetDb = interpolateGainDb(centerHz, preset.bandGainsDb)
                val targetMb = (targetDb * 100f).roundToInt().coerceIn(minMb, maxMb).toShort()
                eq.setBandLevel(b.toShort(), targetMb)
            }
            eq.enabled = true
            equalizer = eq
        }

        // 2. Controlled BassBoost
        if (preset.bassBoostStrength > 0) {
            runCatching {
                val bb = BassBoost(0, audioSessionId)
                if (bb.strengthSupported) {
                    bb.setStrength(preset.bassBoostStrength)
                    bb.enabled = true
                    bassBoost = bb
                } else {
                    bb.release()
                }
            }
        }

        // 3. Subtle Virtualizer soundstage
        attachVirtualizerOnly(audioSessionId, preset)

        // 4. Gentle LoudnessEnhancer (never over-driven)
        if (preset.loudnessGainMb > 0) {
            runCatching {
                val le = LoudnessEnhancer(audioSessionId)
                le.setTargetGain(preset.loudnessGainMb)
                le.enabled = true
                loudnessEnhancer = le
            }
        }
    }

    private fun interpolateGainDb(centerHz: Float, gains: FloatArray): Float {
        if (centerHz <= TEN_BAND_CENTER_HZ.first()) return gains.first()
        if (centerHz >= TEN_BAND_CENTER_HZ.last()) return gains.last()
        for (i in 0 until TEN_BAND_CENTER_HZ.lastIndex) {
            val f0 = TEN_BAND_CENTER_HZ[i]
            val f1 = TEN_BAND_CENTER_HZ[i + 1]
            if (centerHz in f0..f1) {
                val t = ((centerHz - f0) / (f1 - f0)).coerceIn(0f, 1f)
                return gains[i] + (gains[i + 1] - gains[i]) * t
            }
        }
        return 0f
    }
}
