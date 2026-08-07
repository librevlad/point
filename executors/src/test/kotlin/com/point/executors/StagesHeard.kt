package com.point.executors

import com.point.core.flow.ActionProgress
import kotlinx.coroutines.withContext

internal suspend fun stagesHeard(action: suspend () -> Unit): List<String> {
    val heard = mutableListOf<String>()
    withContext(ActionProgress { heard += it }) { action() }
    return heard
}
