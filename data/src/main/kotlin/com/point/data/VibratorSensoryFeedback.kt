package com.point.data

import android.content.Context
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.point.core.flow.SensoryFeedback
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * Predefined haptic primitives via the system [Vibrator] (MOTION.md M4). Effects use
 * USAGE_TOUCH attributes, so the user's system "touch feedback" setting governs them —
 * no in-app toggle needed. Every call is a best-effort no-op on failure or when the
 * device has no vibrator; feedback must never break a flow.
 */
class VibratorSensoryFeedback @Inject constructor(
    @ApplicationContext private val context: Context,
) : SensoryFeedback {

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

    override fun tap() = play(VibrationEffect.EFFECT_CLICK)

    override fun success() = play(VibrationEffect.EFFECT_TICK)

    override fun failure() = play(VibrationEffect.EFFECT_DOUBLE_CLICK)

    private fun play(effectId: Int) {
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
}
