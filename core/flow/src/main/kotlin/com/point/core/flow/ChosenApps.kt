package com.point.core.flow

import com.point.core.model.ObjectKind

/** An external app the user actually opened this kind of object in (#66 slice 4). */
data class ChosenApp(
    val kind: ObjectKind,
    val packageName: String,
    val activity: String,
    val label: String,
)

/**
 * Remembered app choices — the seed for synthesising per-app capabilities, so real
 * device apps compete inside the SAME derived graph as built-in actions and rise
 * through the same learning policy. [all] is a warm, sync snapshot (capability sets
 * are built at process start — no I/O on the render path); [record] persists a pick.
 */
interface ChosenApps {
    fun all(): List<ChosenApp>
    suspend fun record(app: ChosenApp)
}
