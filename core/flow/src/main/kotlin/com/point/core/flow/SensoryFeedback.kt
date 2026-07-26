package com.point.core.flow

/**
 * The hand-feel of the flow (MOTION.md M4, принцип №7 — «каждое действие приносит
 * микроскопическую радость»). Three moments, nothing more: the tap that launches an
 * action, the success that lands, the failure that bumps. Implementations must respect
 * the system's haptic settings and be a silent no-op where there is no vibrator.
 * Branded *sound* is a later slice — it needs the owner's taste on the samples.
 */
interface SensoryFeedback {
    /** The instant an action is chosen — a light, dry click. */
    fun tap()

    /** An action finished well (a Done terminal or a new object) — a confirming tick. */
    fun success()

    /** An action failed — a short double bump, never a long angry buzz. */
    fun failure()
}
