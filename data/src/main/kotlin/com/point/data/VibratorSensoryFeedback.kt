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

/**
 * The hand-and-ear of the flow (MOTION.md M4): predefined haptic primitives via the system
 * [Vibrator] plus the three branded synthesized samples (res/raw, «как у Leica» — тихо, сухо,
 * коротко). Haptics use USAGE_TOUCH attributes, so the user's system "touch feedback" setting
 * governs them; sound obeys [SensorySettings] (default ON). Every call is a best-effort no-op
 * on failure — feedback must never break a flow.
 */
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
            val effect = VibrationEffect.createPredefined(effectId)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                v.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH))
            } else {
                v.vibrate(effect)
            }
        }
    }

    private companion object { const val VOLUME = 0.6f }
}
