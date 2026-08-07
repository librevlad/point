package com.point.core.flow

import com.point.core.model.ObjectKind

data class ChosenApp(
    val kind: ObjectKind,
    val packageName: String,
    val activity: String,
    val label: String,
)

interface ChosenApps {
    fun all(): List<ChosenApp>
    suspend fun record(app: ChosenApp)
}
