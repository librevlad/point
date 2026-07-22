package com.point

import com.point.core.model.Bubble
import com.point.core.model.CapabilityId
import com.point.core.model.FavoriteChain
import com.point.core.model.PointObject

/**
 * One entry on the navigation stack: an object, the bubbles it offers, and the
 * capability that produced it (null for the root). The `via*` provenance is the
 * flow journal from which a favorite chain is built.
 */
data class FlowFrame(
    val obj: PointObject,
    val bubbles: List<Bubble>,
    val viaCapability: CapabilityId? = null,
    val viaTitle: String? = null,
    /** For a COLLECTION: its items (files), loaded async after the frame is pushed. */
    val items: List<PointObject> = emptyList(),
    /** For a TEXT object: a bounded preview of its content, loaded async. */
    val textPreview: String? = null,
)

/** Immutable UI state rendered by the host. */
data class FlowUiState(
    val loading: Boolean = false,
    val frame: FlowFrame? = null,
    /** Transient text from the ActionResult channel (Failure / Done). */
    val message: String? = null,
    /** Non-null while a capability awaits free-text input (NeedsInput). */
    val inputPrompt: String? = null,
    /** Saved chains applicable to the current object (first step accepts it). */
    val favorites: List<FavoriteChain> = emptyList(),
    /** True when the current flow has ≥1 applied step that can be saved. */
    val canSaveChain: Boolean = false,
)
