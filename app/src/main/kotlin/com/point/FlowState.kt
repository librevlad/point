package com.point

import com.point.core.flow.UsageSummary
import com.point.core.flow.UserAiConfig
import com.point.core.model.Bubble
import com.point.core.model.CapabilityId
import com.point.core.model.FavoriteChain
import com.point.core.model.Intent
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
    /** Non-null while an action runs: a short label of WHAT is happening (e.g. the
     *  action's title), so the busy screen shows progress instead of a blank spinner. */
    val busy: String? = null,
    val frame: FlowFrame? = null,
    /** Transient text from the ActionResult channel (Failure / Done). */
    val message: String? = null,
    /** Non-null while a capability awaits free-text input (NeedsInput). */
    val inputPrompt: String? = null,
    /** Saved chains applicable to the current object (first step accepts it). */
    val favorites: List<FavoriteChain> = emptyList(),
    /** True when the current flow has ≥1 applied step that can be saved. */
    val canSaveChain: Boolean = false,
    /** Applicable user intents for the current object — the intent-first surface. */
    val intents: List<Intent> = emptyList(),
    /** A selected intent shows its capabilities; null shows the intents. */
    val selectedIntent: Intent? = null,
    /** Capabilities serving [selectedIntent] (shown when it is non-null). */
    val intentBubbles: List<Bubble> = emptyList(),
    /** Non-null while the bring-your-own AI-key screen is shown (its prefilled values). */
    val keyScreen: UserAiConfig? = null,
    /** On the key screen: whether the private usage journal is on, and its current tally. */
    val usageEnabled: Boolean = false,
    val usageSummary: UsageSummary? = null,
)
