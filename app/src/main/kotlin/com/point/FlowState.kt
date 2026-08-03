package com.point

import com.point.core.flow.AppTarget
import com.point.core.flow.UsageSummary
import com.point.core.flow.UserAiConfig
import com.point.core.model.Bubble
import com.point.core.model.CapabilityId
import com.point.core.model.ChatMessage
import com.point.core.flow.CapabilityMeta
import com.point.core.flow.Latency
import com.point.core.model.FavoriteChain
import com.point.core.model.LatentBubble
import com.point.core.model.ObjectKind
import com.point.core.model.PointObject
import com.point.core.model.Preview
import com.point.core.model.Relation

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
    /** Things extraction found *inside* this object (#222) — the waybill number, the branch
     *  address, the deadline. Not the same as [items]: those are files the object contains,
     *  these are things the world contains that the object mentions. Grows as waves land. */
    val found: List<PointObject> = emptyList(),
    /** How the [found] objects relate to this one and to each other — provenance for now
     *  («read off that page»), roles once the classifier lands (#222, шаг 6). */
    val relations: List<Relation> = emptyList(),
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

/**
 * Экран ожидания поднимается только над нетихой работой (M3) — облачной или объявленной долгой.
 *
 * Условие вынуто из `PointHost` в чистую функцию не ради красоты: «тихая работа не поднимает
 * экран» — обещание, которое надо проверять тестом, а не глазами на устройстве.
 */
fun showsBusyScreen(ui: FlowUiState): Boolean = ui.busy != null && !ui.busyQuiet

/** Работает сам объект: тихая работа идёт, экран остался на нём, кольцо-раздумье живёт.
 *  Не путать с [quietWork] — та про способность («такой работе экран не нужен»), эта про
 *  происходящее прямо сейчас. */
fun objectWorking(ui: FlowUiState): Boolean = ui.busy != null && ui.busyQuiet

/**
 * Что действие говорит о себе, когда экрана ожидания нет (#288).
 *
 * Быстрые действия идут БЕЗ экрана ожидания — и до сих пор их стадии умирали в состоянии: канал
 * [com.point.core.flow.ActionProgress] доносил слова до `FlowViewModel`, а рисовать их было
 * негде. «Скан», «В Word», «В PDF», «Страницы», «Распаковать» на большом файле работают секунды
 * и молчали ровно так же, как раньше молчало всё: снаружи тишина неотличима от «зависло».
 *
 * Говорит **тот же** `busyStage`, что показывает экран ожидания, — второго механизма нет, и
 * разъехаться словам негде. Пусто, пока действие молчит: выдумывать за него шаги — та самая
 * подмена статуса имитацией, против которой весь срез.
 */
fun quietStage(ui: FlowUiState): String? = ui.busyStage?.takeIf { objectWorking(ui) }

/** One node of the visible Object Timeline (#114): what the object was at that step,
 *  and the action that made it (null for the root). The philosophy made visible —
 *  the flow is a journey of transformations, not a stack of screens. */
data class PathStep(val kind: ObjectKind, val via: String?)

/** Immutable UI state rendered by the host. */
data class FlowUiState(
    /** Non-null while an action runs: a short label of WHAT is happening (e.g. the
     *  action's title), so the busy screen shows progress instead of a blank spinner. */
    val busy: String? = null,
    /** Что действие делает СЕЙЧАС — его собственные слова (#288). null — реализатор молчит,
     *  и экран не выдумывает за него шаги: показывает время и отмену.
     *
     *  Читают двое, и оба берут ОДНУ строку: экран ожидания у нетихой работы ([showsBusyScreen])
     *  и сам объект у тихой ([quietStage]). Стадия живёт ровно столько, сколько занятость, её
     *  породившая: каждое новое [busy] обнуляет её, иначе над новой работой висели бы слова
     *  предыдущей — подмена статуса, только чужими словами вместо выдуманных. */
    val busyStage: String? = null,
    /** True when the running action is a cloud/AI call — the busy screen then reassures with
     *  cloud-flavoured, time-advancing stages instead of a frozen wheel (#62). */
    val busyNetwork: Boolean = false,
    /** M3 (MOTION.md №8): true while a fast local action runs — the object stays on screen
     *  and "works" (thinking ring) instead of a full busy screen; states flow, never snap. */
    val busyQuiet: Boolean = false,
    val frame: FlowFrame? = null,
    /** Non-null while the AI chat (#4) is open over the current object — a multi-turn conversation. */
    val chat: ChatState? = null,
    /** The Object Timeline (#114): the whole journey, root first — tap a node to jump back. */
    val path: List<PathStep> = emptyList(),
    /** Transient text from the ActionResult channel (Failure / Done). */
    val message: String? = null,
    /**
     * Исход [message] — отказ или нет.
     *
     * Раньше поля не было и сообщение красилось тревожным всегда: пока баннер стоял внизу
     * прокрутки, этого никто не видел (#358). Как только исход подняли под объект, «Открываю
     * в Excel» стало кричать красным наравне с настоящим отказом — а цвет здесь и есть
     * сообщение, второе после текста.
     *
     * Карточка исхода рисует по этому флагу **знак**: «✓» светом портала против «✕» тёплым
     * концом фирменного градиента. Поэтому его нельзя ронять «по умолчанию»: `copy` без него
     * тащит прошлое значение, и удача после отказа получила бы чужой знак.
     */
    val messageIsFailure: Boolean = false,
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
    /** Экран выделения (#259): открыт, пока не взяли захват или не вышли назад. */
    val selection: SelectionUi? = null,
    /** Экран поиска по документу (#279): открыт, пока не вышли назад. */
    val find: FindUi? = null,
)

/**
 * Живое состояние экрана выделения (#259): страница (EXIF-выпрямленная копия), построчная
 * подсветка захвата и его текст — подсветка и рамки в координатах [image]. [text] `null` —
 * ещё не обводили; пустой — обвели место без слов (честное состояние, кнопка «Взять» гаснет).
 */
data class SelectionUi(
    val image: androidx.compose.ui.graphics.ImageBitmap,
    val highlights: List<com.point.core.flow.Box> = emptyList(),
    val text: String? = null,
)

/**
 * Живое состояние экрана поиска (#279): та же страница, что у выделения, и рамки найденных мест
 * в координатах [image].
 *
 * [status] — что сказано про находки; `null` значит «ещё не искали». Различие несущее: пустое
 * поле — не неудачный поиск, и «Ничего не нашлось» под нетронутым полем было бы ответом на
 * вопрос, которого человек не задавал.
 */
data class FindUi(
    val image: androidx.compose.ui.graphics.ImageBitmap,
    val highlights: List<com.point.core.flow.Box> = emptyList(),
    val status: String? = null,
)


/** The AI chat with the current object (#4). A multi-turn conversation grounded in [obj]; [pending]
 *  is true while the model answers. [suggestions] are the opening prompts shown on an empty thread. */
data class ChatState(
    val obj: PointObject,
    val messages: List<ChatMessage> = emptyList(),
    val pending: Boolean = false,
    val suggestions: List<String> = emptyList(),
)

/** «Компьютер» (#147): the pairing screen's state — current pairing + a busy/error line. */
data class PcScreenState(
    val pairing: com.point.core.flow.PcPairing? = null,
    val discovered: List<com.point.core.flow.DiscoveredPc> = emptyList(),
    val busy: Boolean = false,
    val error: String? = null,
)
