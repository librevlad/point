package com.point.data

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.point.core.flow.SensoryFeedback
import com.point.core.flow.SensorySettings
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

class VibratorSensoryFeedback @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SensorySettings,
) : SensoryFeedback {

    private val sounds: SoundPool? by lazy {
        runCatching {
            SoundPool.Builder()
                .setMaxStreams(2)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                .build()
        }.getOrNull()
    }
    private val tickSound by lazy { runCatching { sounds?.load(context, R.raw.point_tick, 1) }.getOrNull() }
    private val successSound by lazy { runCatching { sounds?.load(context, R.raw.point_success, 1) }.getOrNull() }
    private val failureSound by lazy { runCatching { sounds?.load(context, R.raw.point_failure, 1) }.getOrNull() }

    private val vibrator: Vibrator? by lazy {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)
                    ?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
        }.getOrNull()?.takeIf { it.hasVibrator() }
    }

    override fun tap() = play(VibrationEffect.EFFECT_CLICK, tickSound)

    override fun success() = play(VibrationEffect.EFFECT_TICK, successSound)

    override fun failure() = play(VibrationEffect.EFFECT_DOUBLE_CLICK, failureSound)

    private fun play(effectId: Int, soundId: Int?) {
        if (soundId != null && runCatching { settings.isSoundEnabled() }.getOrDefault(true)) {
            runCatching { sounds?.play(soundId, VOLUME, VOLUME, 1, 0, 1f) }
        }
        runCatching {
            val v = vibrator ?: return
            val effect = effectOf(effectId)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                v.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH))
            } else {
                v.vibrate(effect)
            }
        }
    }

    // Готовые системные образцы появились в Android 10; раньше — свои импульсы того же
    // характера, иначе на 8.0–9.0 отклик тихо пропадал бы целиком.
    private fun effectOf(effectId: Int): VibrationEffect =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            VibrationEffect.createPredefined(effectId)
        } else {
            when (effectId) {
                VibrationEffect.EFFECT_DOUBLE_CLICK ->
                    VibrationEffect.createWaveform(longArrayOf(0, 20, 60, 20), -1)
                VibrationEffect.EFFECT_TICK ->
                    VibrationEffect.createOneShot(10, VibrationEffect.DEFAULT_AMPLITUDE)
                else ->
                    VibrationEffect.createOneShot(20, VibrationEffect.DEFAULT_AMPLITUDE)
            }
        }

    private companion object { const val VOLUME = 0.6f }
}
