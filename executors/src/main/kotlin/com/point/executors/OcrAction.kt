package com.point.executors

import com.point.core.flow.AI_CHAIN_PRIVACY
import com.point.core.flow.AtomRecognizer
import com.point.core.flow.Capability
import com.point.core.flow.capabilities.OcrCapability
import com.point.core.flow.CapabilityMeta
import com.point.core.flow.CloudPrivacySettings
import com.point.core.flow.Cost
import com.point.core.flow.ExternalEye
import com.point.core.flow.Latency
import com.point.core.flow.LlmClient
import com.point.core.flow.META_OCR_TEXT_REF
import com.point.core.flow.META_READ_UPSCALE
import com.point.core.flow.META_READING_MODE
import com.point.core.flow.ObjectStore
import com.point.core.flow.PrivacyLevel
import com.point.core.flow.allowedAt
import com.point.core.flow.META_READING_DOUBT
import com.point.core.flow.degeneratedReading
import com.point.core.flow.focusOf
import com.point.core.flow.investigationKey
import com.point.core.flow.InvestigationState
import com.point.core.flow.KnownCapabilities
import com.point.core.flow.NO_TEXT_CLAUSE
import com.point.core.flow.noTextAnswer
import com.point.core.flow.readingDoubts
import com.point.core.flow.poorlyRead
import com.point.core.flow.stripMarkdownChrome
import com.point.core.flow.Realizer
import com.point.core.flow.RealizerKind
import com.point.core.flow.RealizerMeta
import com.point.core.flow.TextRecognizer
import com.point.core.flow.reportStage
import com.point.core.model.ActionResult
import com.point.core.model.ActionYield
import com.point.core.model.CapabilityId
import com.point.core.model.Findings
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ResultObject
import com.point.core.model.ScratchRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/** Просьба к зрячей модели — общая на все облачные пути (#840). */
internal val OCR_CLOUD_PROMPT: String = com.point.core.flow.CLOUD_READING_PROMPT

/**
 * Узкий вопрос вместо вопроса о странице (#426): человек показал область, и на картинке
 * теперь только она. Замер 04.08.2026 показал, чем опасен широкий вопрос — модель уверенно
 * отдаёт числа с шильдика вместо показания, и человек получает то, чего не было.
 */
internal const val OCR_FOCUS_PROMPT =
    "На изображении только один фрагмент документа. Прочитай его дословно, целиком и ничего " +
        "не добавляя от себя. Не додумывай то, что обрезано краем. Верни только текст. " +
        NO_TEXT_CLAUSE

internal fun ocrPromptFor(obj: PointObject): String =
    if (focusOf(obj.metadata, obj.id)?.region != null) OCR_FOCUS_PROMPT else OCR_CLOUD_PROMPT

internal const val OCR_CLOUD_STAGE = "Читаю снимок в облаке"

class DeviceOcrRealizer @Inject constructor(
    private val store: ObjectStore,
    private val recognizer: TextRecognizer,
) : Realizer {
    override val capabilityId = OcrCapability.ID
    override val meta = RealizerMeta(priority = 10, kind = RealizerKind.LOCAL)

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        withContext(Dispatchers.IO) {

            val cached = input.metadata[META_OCR_TEXT_REF]
                ?.let { path -> runCatching { File(path).takeIf(File::isFile)?.readText() }.getOrNull() }
            if (!cached.isNullOrBlank()) {
                return@withContext ActionResult.Success(
                    ResultObject(
                        ObjectKind.TEXT,
                        "text/plain",
                        ScratchRef(input.metadata.getValue(META_OCR_TEXT_REF)),
                        ocrMeta(input),
                    ),
                )
            }

            reportStage("Читаю текст на устройстве")

            // Сначала спрашиваем сам движок: он отдаёт уверенность по каждому слову.
            // Где уверенности нет, судить остаётся по составу ответа (#694).
            val layer = (recognizer as? AtomRecognizer)?.let { runCatching { it.read(input) }.getOrNull() }
            val text = layer?.text ?: runCatching { recognizer.recognize(input) }.getOrDefault("")
            if (poorlyRead(text, layer)) {

                return@withContext ActionResult.Failure("Не разобрал текст на этом снимке", recoverable = true)
            }
            runCatching {
                val ref = store.newScratchFile("txt")
                File(ref.value).writeText(text)
                ActionResult.Success(
                    ResultObject(
                        ObjectKind.TEXT,
                        "text/plain",
                        ref,
                        ocrMeta(input),
                    ),
                )
            }.getOrElse { ActionResult.Failure(it.message ?: "Ошибка записи результата", recoverable = true) }
        }
}

class ExternalEyeOcrRealizer @Inject constructor(
    private val eye: ExternalEye,
    private val store: ObjectStore,
) : Realizer {
    override val capabilityId = OcrCapability.ID
    override val meta = RealizerMeta(priority = 50, kind = RealizerKind.CLOUD)

    override fun isAvailable(): Boolean = eye.available()

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        withContext(Dispatchers.IO) { readWithExternalEye(eye, store, input) }
}

class CloudOcrRealizer @Inject constructor(
    private val llm: LlmClient,
    private val privacy: CloudPrivacySettings,
) : Realizer {
    override val capabilityId = OcrCapability.ID
    override val meta = RealizerMeta(priority = 90, kind = RealizerKind.CLOUD)

    override fun isAvailable(): Boolean = allowedAt(privacy.level(), AI_CHAIN_PRIVACY)

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        withContext(Dispatchers.IO) {
            if (!isAvailable()) return@withContext ActionResult.Failure(chainClosed(privacy.level()), recoverable = true)

            reportStage(OCR_CLOUD_STAGE)
            runCatching { guarded(input, llm.run(input, ocrPromptFor(input)), "модель") }
                .getOrElse { ActionResult.Failure(it.message ?: "Ошибка распознавания в облаке", recoverable = true) }
        }
}

class CloudOcrCapability @Inject constructor() : Capability {
    override val id = ID
    override val icon = "ocr-cloud"
    override val meta = CapabilityMeta(cost = Cost.PAID, latency = Latency.SLOW, network = true, auth = true)

    override fun label(state: ObjectState) = "Прочитать сильнее"
    override fun accepts(state: ObjectState) = state.kind == ObjectKind.IMAGE

    // Знание того же объекта, а не новый объект (#1097): текст ложится на исходник.
    override fun produces(state: ObjectState) = state.with(com.point.core.model.Feature.HAS_TEXT)

    override fun yields(state: ObjectState) = ActionYield.Same("текст точнее · снимок уйдёт в сервис")

    override fun intents(state: ObjectState) = setOf(com.point.core.model.Intent.UNDERSTAND)

    companion object { val ID = CapabilityId("ocr-cloud") }
}

class ExternalEyeCloudOcrRealizer @Inject constructor(
    private val eye: ExternalEye,
    private val store: ObjectStore,
) : Realizer {
    override val capabilityId = CloudOcrCapability.ID
    override val meta = RealizerMeta(priority = 5, kind = RealizerKind.CLOUD)

    override fun isAvailable(): Boolean = eye.available()

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        withContext(Dispatchers.IO) { readWithExternalEye(eye, store, input, asKnowledge = true) }
}

class CloudOcrDirectRealizer @Inject constructor(
    private val llm: LlmClient,
    private val privacy: CloudPrivacySettings,
    private val store: ObjectStore,
) : Realizer {
    override val capabilityId = CloudOcrCapability.ID
    override val meta = RealizerMeta(priority = 10, kind = RealizerKind.CLOUD)

    override fun isAvailable(): Boolean = allowedAt(privacy.level(), AI_CHAIN_PRIVACY)

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        withContext(Dispatchers.IO) {
            if (!isAvailable()) return@withContext ActionResult.Failure(chainClosed(privacy.level()), recoverable = true)
            reportStage(OCR_CLOUD_STAGE)
            runCatching {
                val answer = llm.run(input, ocrPromptFor(input))
                val page = stripMarkdownChrome(File(answer.uri.value).readText())
                if (noTextAnswer(page)) return@runCatching noTextFound(input)
                degeneratedReading(page)?.let { why ->
                    return@runCatching ActionResult.Failure(unreadable("модель", why), recoverable = true)
                }
                strongerReadingLands(store.newScratchFile("txt"), page)
            }.getOrElse { ActionResult.Failure(it.message ?: "Ошибка распознавания в облаке", recoverable = true) }
        }
}

/**
 * Сильное чтение — знание того же объекта, а не новый объект (#1097, #1009).
 *
 * «Прочитать сильнее» рождало дочерний TEXT: исправленная ошибка оставалась у ребёнка, а
 * исходник продолжал носить неверное значение, и ни один экран не говорил, что есть второе
 * прочтение. Теперь текст ложится представлением на исходный узел — тем же ключом
 * `ocr.text.ref`, каким лежит любое чтение, — и цикл понимания перечитывает сущности по
 * новому тексту: два прочтения встречаются в одном месте, через обычный merge, с сохранённым
 * расхождением (ADR-0001 §9). Отдельный объект остаётся за явным «Распознать текст».
 */
private fun strongerReadingLands(
    ref: ScratchRef,
    page: String,
    extras: Map<String, String> = emptyMap(),
): ActionResult = runCatching {
    File(ref.value).writeText(page)
    ActionResult.Done(
        "Прочитано сильнее — текст объекта обновлён",
        com.point.core.model.Findings(
            features = setOf(com.point.core.model.Feature.HAS_TEXT),
            metadata = buildMap {
                put(META_OCR_TEXT_REF, ref.value)
                put(
                    com.point.core.flow.investigationKey(com.point.core.flow.KnownCapabilities.IMAGE_TEXT),
                    com.point.core.flow.InvestigationState.FOUND.wire,
                )
                readingDoubts(page).takeIf { it.isNotEmpty() }?.let { doubts ->
                    put(META_READING_DOUBT, doubts.joinToString("; ") { it.what })
                }
                putAll(extras)
            },
        ),
    )
}.getOrElse { ActionResult.Failure(it.message ?: "Ошибка записи результата", recoverable = true) }

private suspend fun readWithExternalEye(
    eye: ExternalEye,
    store: ObjectStore,
    input: PointObject,

    /** Явный «Распознать текст» рождает вещь; сильное чтение — знание исходника (#1097). */
    asKnowledge: Boolean = false,
): ActionResult {
    reportStage(OCR_CLOUD_STAGE)
    val reading = runCatching { eye.read(input) }.getOrElse {
        return ActionResult.Failure(it.message ?: "Прочитать снаружи не удалось", recoverable = true)
    }

    // Markdown-обёртка провайдера — не текст страницы (#661): «![img-0.jpeg](…)» и
    // «# Відділення 1» уходили в объект и дальше в знание (живой прогон 2026-08-09).
    val page = stripMarkdownChrome(reading.text)
    if (noTextAnswer(page)) return noTextFound(input)
    degeneratedReading(page)?.let { why ->
        return ActionResult.Failure(unreadable(reading.reader, why), recoverable = true)
    }
    if (asKnowledge) {
        val ref = runCatching { store.newScratchFile("txt") }
            .getOrElse { return ActionResult.Failure(it.message ?: "Ошибка записи результата", recoverable = true) }
        return strongerReadingLands(
            ref,
            page,
            buildMap {
                put("engine", reading.reader)
                input.metadata[META_READING_MODE]?.let { put(META_READING_MODE, it) }
            },
        )
    }
    return runCatching {
        val ref = store.newScratchFile("txt")
        File(ref.value).writeText(page)
        ActionResult.Success(
            ResultObject(
                ObjectKind.TEXT,
                "text/plain",
                ref,
                buildMap {
                    put("op", "ocr")

                    put("engine", reading.reader)
                    put("where", reading.where)

                    reading.promise.takeIf { it.isNotBlank() }?.let { put("promise", it) }
                    input.metadata[META_READING_MODE]?.let { put(META_READING_MODE, it) }

                    readingDoubts(page).takeIf { it.isNotEmpty() }?.let { doubts ->
                        put(META_READING_DOUBT, doubts.joinToString("; ") { it.what })
                    }

                    input.metadata[META_READ_UPSCALE]?.let { put(META_READ_UPSCALE, it) }
                },
            ),
        )
    }.getOrElse { ActionResult.Failure(it.message ?: "Ошибка записи результата", recoverable = true) }
}

private fun guarded(input: PointObject, result: ResultObject, who: String): ActionResult {
    val text = runCatching { File(result.uri.value).readText() }.getOrNull() ?: return ActionResult.Success(result)
    if (noTextAnswer(text)) return noTextFound(input)
    val why = degeneratedReading(text) ?: return ActionResult.Success(result)
    return ActionResult.Failure(unreadable(who, why), recoverable = true)
}

/**
 * Читатель посмотрел и текста не увидел (#1054): отдал пустой лист или служебную пометку
 * вроде «*[No text detected]*». Это ответ на вопрос «что написано на снимке» — «смотрели,
 * не нашлось», — а не текст: объект не заводится, знание ложится на исходник, и человек
 * слышит свои слова, а не чужую отписку. Под Focus вопрос другой — «что в этой области», —
 * и «не нашлось» остаётся в её рамках, а не закрывает вопрос о снимке целиком.
 *
 * Не срыв: читатель ответил. Срыв оставил бы вопрос не исследованным (ADR-0001 §9).
 */
private fun noTextFound(input: PointObject): ActionResult = ActionResult.Done(
    NO_TEXT_ON_PICTURE,
    Findings(
        metadata = mapOf(
            investigationKey(KnownCapabilities.IMAGE_TEXT, focusOf(input.metadata, input.id)) to
                InvestigationState.NOT_FOUND.wire,
        ),
    ),
)

internal const val NO_TEXT_ON_PICTURE = "Текста на снимке не нашлось"

private fun unreadable(who: String, why: String): String =
    "Не смог прочитать этот снимок: $who отдала бессмыслицу ($why). " +
        "Лучше переснять при ровном свете и поближе."

/**
 * Слова отказа по режиму приватности — общие (#840): они были записаны здесь, в `:data` и в
 * `:core:flow` тремя копиями, и любая правка формулировки расходилась молча.
 */
private fun chainClosed(level: PrivacyLevel): String =
    com.point.core.flow.chainClosedBy(level)

private fun ocrMeta(input: PointObject): Map<String, String> = buildMap {
    put("op", "ocr")
    put("engine", "on-device")
    input.metadata[META_READING_MODE]?.let { put(META_READING_MODE, it) }
    input.metadata[META_READ_UPSCALE]?.let { put(META_READ_UPSCALE, it) }
}
