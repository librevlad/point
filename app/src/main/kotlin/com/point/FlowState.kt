package com.point

import com.point.core.flow.AppTarget
import com.point.core.flow.UsageSummary
import com.point.core.flow.UserAiConfig
import com.point.core.model.Bubble
import com.point.core.model.CapabilityId
import com.point.core.flow.CapabilityMeta
import com.point.core.flow.Latency
import com.point.core.model.FavoriteChain
import com.point.core.model.LatentBubble
import com.point.core.model.ObjectKind
import com.point.core.model.PointObject
import com.point.core.model.Preview

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
    /** For an IMAGE object: its real thumbnail (EXIF-upright, downsampled), loaded async —
     *  the hero of the screen is the object itself, not a kind icon (#114). */
    val preview: androidx.compose.ui.graphics.ImageBitmap? = null,
    /** Almost-applicable capabilities (#97): shown dimmed with what each still needs. */
    val latent: List<LatentBubble> = emptyList(),
    /** Labels of still-running background enrichment («Распознаю текст…») — the visible
     *  "Point думает" feedback; empty when understanding is complete (#64). */
    val enriching: List<String> = emptyList(),
    /** Discover (#114): one folded, never-tried action offered as a «💡 Попробуйте» hint. */
    val discover: Bubble? = null,
    /** User rule (#66): the action pinned first for this object kind, if any. */
    val pinned: CapabilityId? = null,
)

/** The file the header preview is decoded from (#114): an image shows itself, a PDF its
 *  rendered first page; everything else keeps the kind icon (null). Failures are null too —
 *  a preview is a gift, never an error. */
suspend fun previewSource(obj: PointObject, rasterizer: com.point.core.flow.PdfRasterizer): String? =
    when (obj.state.kind) {
        ObjectKind.IMAGE -> obj.uri.value
        ObjectKind.PDF -> runCatching { rasterizer.rasterizeFirstPage(obj)?.value }.getOrNull()
        else -> null
    }

/** M3: work that may run quietly on the object itself — local and not declared slow.
 *  Cloud (network) and SLOW work keep the full busy screen with its staged reassurance. */
fun quietWork(meta: CapabilityMeta): Boolean = !meta.network && meta.latency != Latency.SLOW

/** One node of the visible Object Timeline (#114): what the object was at that step,
 *  and the action that made it (null for the root). The philosophy made visible —
 *  the flow is a journey of transformations, not a stack of screens. */
data class PathStep(val kind: ObjectKind, val via: String?)

/** Immutable UI state rendered by the host. */
data class FlowUiState(
    /** Non-null while an action runs: a short label of WHAT is happening (e.g. the
     *  action's title), so the busy screen shows progress instead of a blank spinner. */
    val busy: String? = null,
    /** True when the running action is a cloud/AI call — the busy screen then reassures with
     *  cloud-flavoured, time-advancing stages instead of a frozen wheel (#62). */
    val busyNetwork: Boolean = false,
    /** M3 (MOTION.md №8): true while a fast local action runs — the object stays on screen
     *  and "works" (thinking ring) instead of a full busy screen; states flow, never snap. */
    val busyQuiet: Boolean = false,
    val frame: FlowFrame? = null,
    /** The Object Timeline (#114): the whole journey, root first — tap a node to jump back. */
    val path: List<PathStep> = emptyList(),
    /** Transient text from the ActionResult channel (Failure / Done). */
    val message: String? = null,
    /** Non-null while a capability awaits free-text input (NeedsInput). */
    val inputPrompt: String? = null,
    /** Ready-made answers for [inputPrompt] — the 3 likely AI prompts to tap instead of typing (#86). */
    val inputSuggestions: List<String> = emptyList(),
    /** Non-null while a capability awaits a picked image (NeedsImage) — the host opens the photo picker. */
    val needsImage: String? = null,
    /** Saved chains applicable to the current object (first step accepts it). */
    val favorites: List<FavoriteChain> = emptyList(),
    /** True when the current flow has ≥1 applied step that can be saved. */
    val canSaveChain: Boolean = false,
    /** True while a cloud action waits for one-time privacy consent (#10). */
    val cloudConsent: Boolean = false,
    /** Non-null while a capability's pre-execution preview awaits confirm (#97). */
    val preview: Preview? = null,
    /** Non-null while the inline "Открыть в…" app-picker is shown — the installed apps that can
     *  open the current object (#66 device actions). */
    val appPicker: List<AppTarget>? = null,
    /** Non-null while the bring-your-own AI-key screen is shown (its prefilled values). */
    val keyScreen: UserAiConfig? = null,
    val pcScreen: PcScreenState? = null,
    /** On the key screen: whether the private usage journal is on, and its current tally. */
    val usageEnabled: Boolean = false,
    /** On the key screen: whether branded action sounds are on (MOTION.md M4). */
    val soundEnabled: Boolean = true,
    val usageSummary: UsageSummary? = null,
)


/** «Компьютер» (#147): the pairing screen's state — current pairing + a busy/error line. */
data class PcScreenState(
    val pairing: com.point.core.flow.PcPairing? = null,
    val busy: Boolean = false,
    val error: String? = null,
)
