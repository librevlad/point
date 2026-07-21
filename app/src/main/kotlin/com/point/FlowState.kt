package com.point

import com.point.core.model.Bubble
import com.point.core.model.PointObject

/** One entry on the navigation stack: an object and the bubbles it offers. */
data class FlowFrame(
    val obj: PointObject,
    val bubbles: List<Bubble>,
)

/** Immutable UI state rendered by the host. */
data class FlowUiState(
    val loading: Boolean = false,
    val frame: FlowFrame? = null,
    /** Transient text from the ExecutorResult channel (Failure / Done). */
    val message: String? = null,
    /** Non-null while an executor awaits free-text input (NeedsInput). */
    val inputPrompt: String? = null,
)
