package com.point

import com.point.core.flow.AppTarget
import com.point.core.ui.Outcome
import com.point.core.flow.AiServiceLine
import com.point.core.flow.UserAiKeys
import com.point.core.model.Bubble
import com.point.core.model.CapabilityId
import com.point.core.model.ChatMessage
import com.point.core.flow.CapabilityMeta
import com.point.core.flow.Latency
import com.point.core.model.LatentBubble
import com.point.core.model.ObjectKind
import com.point.core.model.PointObject
import com.point.core.model.Preview
import com.point.core.model.Relation

data class FlowFrame(
    val obj: PointObject,
    val bubbles: List<Bubble>,
    val viaCapability: CapabilityId? = null,
    val viaTitle: String? = null,

    val items: List<PointObject> = emptyList(),

    val itemsTotal: Int = 0,

    val itemsTotalAtLeast: Boolean = false,

    val found: List<PointObject> = emptyList(),

    val relations: List<Relation> = emptyList(),

    val textPreview: String? = null,

    /** Предпросмотр упёрся в свой предел чтения — «Показать целиком» честно об этом скажет. */
    val textPreviewTruncated: Boolean = false,

    val preview: androidx.compose.ui.graphics.ImageBitmap? = null,

    val latent: List<LatentBubble> = emptyList(),

    val enriching: List<String> = emptyList(),

    val pinned: CapabilityId? = null,

    val focus: com.point.core.flow.Focus? = null,

    /** Состояние операций, не знания: исследования, которые не удались (ADR-0001 §9, §18). */
    val failed: List<com.point.core.flow.FailedInvestigation> = emptyList(),
)

/**
 * Причина, по которой страницы не вышло, здесь не гасится (#570): именно из неё человеку
 * достаются слова о пустом или испорченном документе. Ловит её вызывающий.
 */
suspend fun previewSource(obj: PointObject, rasterizer: com.point.core.flow.PdfRasterizer): String? =
    when (obj.state.kind) {
        ObjectKind.IMAGE -> obj.uri.value
        ObjectKind.PDF -> rasterizer.rasterizeFirstPage(obj)?.value
        else -> null
    }

fun quietWork(meta: CapabilityMeta): Boolean = !meta.network && meta.latency != Latency.SLOW

fun showsBusyScreen(ui: FlowUiState): Boolean = ui.busy != null && !ui.busyQuiet

fun showsCancel(ui: FlowUiState): Boolean = showsBusyScreen(ui) && ui.busyCancelable

fun objectWorking(ui: FlowUiState): Boolean = ui.busy != null && ui.busyQuiet

fun quietStage(ui: FlowUiState): String? = ui.busyStage?.takeIf { objectWorking(ui) }

fun openChatOf(ui: FlowUiState): ChatState? = ui.chat?.takeIf { ui.chatOpen }

fun keyOfferLabel(message: String?): String? =
    if (message != null && com.point.core.flow.refusalNeedsKey(message)) "Задать свой ключ AI" else null

data class KeyErrand(

    val action: String,

    val objectName: String,
)

/**
 * Экран ключей: все известные сервисы списком — имя, что умеет, есть ли ключ и
 * последний факт о нём (#699).
 */
data class AiKeysScreen(

    val keys: UserAiKeys,

    val services: List<AiServiceLine>,

    val checkedLine: String,
)

/** «Проверить все» — одна проверка на каждый сервис по тапу человека. */
const val CHECK_ALL_SERVICES = "*"

data class PathStep(val kind: ObjectKind, val via: String?)

data class FlowUiState(

    val busy: String? = null,

    val busyStage: String? = null,

    val busyNetwork: Boolean = false,

    val busyQuiet: Boolean = false,

    val busyCancelable: Boolean = false,
    val frame: FlowFrame? = null,

    /** Показанная область, в которую Point сейчас смотрит, — картинкой для человека (#757). */
    val focusPreview: androidx.compose.ui.graphics.ImageBitmap? = null,

    val chat: ChatState? = null,

    val chatOpen: Boolean = false,

    val path: List<PathStep> = emptyList(),

    val message: String? = null,

    val messageOutcome: Outcome = Outcome.NONE,

    val inputPrompt: String? = null,

    val inputSuggestions: List<String> = emptyList(),

    val needsImage: String? = null,

    val cloudConsent: Boolean = false,

    val cloudDestination: String = "",

    val cloudTitle: String = "",

    val cloudConfirm: String = "",

    val cloudEnabled: Boolean = false,

    /** Что уже работает и негде увидеть: закрепления, точки входа, память (#821). */
    val pinned: List<PinnedLine> = emptyList(),
    val memory: com.point.core.flow.HistoryFootprint? = null,

    /** Режим «делай лучшее и не спрашивай» (#795). */
    val yoloEnabled: Boolean = false,

    val preview: Preview? = null,

    val appPicker: List<AppTarget>? = null,

    val keyScreen: AiKeysScreen? = null,

    val keyScreenNote: String? = null,

    val keyErrand: KeyErrand? = null,

    val keyChecking: String? = null,

    val keyVerdict: com.point.core.flow.KeyVerdict? = null,

    val keyVerdictFor: String? = null,

    val aiKeySet: Boolean = false,

    val devicesScreen: DevicesScreenState? = null,

    val signIn: com.point.core.flow.SignIn? = null,

    val soundEnabled: Boolean = true,

    val privacyLevel: com.point.core.flow.PrivacyLevel = com.point.core.flow.PrivacyLevel.DEFAULT,

    val selection: SelectionUi? = null,

    val find: FindUi? = null,
)

data class SelectionUi(
    val image: androidx.compose.ui.graphics.ImageBitmap,
    val highlights: List<com.point.core.flow.Box> = emptyList(),
    val text: String? = null,

    /** Слой слов кадра: по нему мазок прилипает к строке, а не остаётся кривой линией. */
    val layer: com.point.core.flow.AtomLayer? = null,
)

data class FindUi(
    val image: androidx.compose.ui.graphics.ImageBitmap,
    val highlights: List<com.point.core.flow.Box> = emptyList(),
    val status: String? = null,
)

data class ChatState(
    val obj: PointObject,
    val messages: List<ChatMessage> = emptyList(),
    val pending: Boolean = false,
    val suggestions: List<String> = emptyList(),

    val notice: String? = null,

    /**
     * Узнанная в сообщении просьба сделать вещь (#804). Разговор её не исполняет — он
     * показывает само действие, и человек решает сам.
     */
    val offer: ChatOffer? = null,
)

/** Действие, предложенное разговором: у него есть имя, цена и исполнитель (#804). */
data class ChatOffer(val capabilityId: CapabilityId, val title: String)

data class DevicesScreenState(
    val email: String = "",
    val devices: List<com.point.core.flow.CircleDevice> = emptyList(),

    val busy: Boolean = false,
    val error: String? = null,

    val loading: Boolean = true,
)

/** Закреплённое действие для показа: чей вид объекта и что закреплено (#821). */
data class PinnedLine(
    val kind: com.point.core.model.ObjectKind,
    val kindLabel: String,
    val actionLabel: String,
)
