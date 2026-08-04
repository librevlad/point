package com.point.executors

import com.point.core.flow.AI_CHAIN_PRIVACY
import com.point.core.flow.Capability
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
import com.point.core.flow.readingDoubts
import com.point.core.flow.looksLikeOcrGarbage
import com.point.core.flow.Realizer
import com.point.core.flow.RealizerKind
import com.point.core.flow.RealizerMeta
import com.point.core.flow.TextRecognizer
import com.point.core.flow.reportStage
import com.point.core.model.ActionResult
import com.point.core.model.ActionYield
import com.point.core.model.CapabilityId
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ResultObject
import com.point.core.model.ScratchRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/** The one prompt for cloud (vision) OCR — shared by the automatic fallback realizer and the
 *  explicit "Распознать в облаке" escalation. */
internal const val OCR_CLOUD_PROMPT =
    "Извлеки весь текст с изображения дословно, сохраняя порядок строк. " +
        "Таблицы оформи в Markdown. Верни только текст, без комментариев."

/** Одна работа — одни слова (#288): облачное чтение снимка выглядит одинаково и как запасное
 *  звено цепочки, и как отдельная кнопка «Распознать в облаке». */
internal const val OCR_CLOUD_STAGE = "Читаю снимок в облаке"

/**
 * photo -> recognised text. Tries on-device OCR first (Tesseract, rus+eng — free,
 * offline, no key or quota), and only falls back to the cloud LLM vision path
 * when on-device finds nothing (hard scans) or the user wants tables as Markdown.
 * The result is a TEXT object, so it chains into translate / to-PDF / save.
 */
class OcrCapability @Inject constructor() : Capability {
    override val id = ID
    override val icon = "ocr"
    /**
     * Ключей и сети не нужно — с обычным случаем справляется устройство. Но **не быстро**, и
     * [Latency.FAST] здесь было прямой неправдой (#288): у чтения страницы бюджет в три минуты
     * (`OCR_READ_BUDGET_MS`), внутри которого помещается до четырёх полных проходов движка.
     *
     * Цена этой неправды была не косметическая. Работа, объявленная нескорой, идёт на экране
     * ожидания — там видно, что действие делает сейчас, и там же живёт кнопка отмены; работа,
     * объявленная быстрой, остаётся на объекте притушенным списком, без единого слова и **без
     * возможности передумать**. То есть самое долгое действие Point было единственным, которое
     * нельзя было ни понять, ни остановить, — ровно то, на что пожаловался владелец.
     *
     * Соседний пузырёк «Распознать в облаке» делает ту же работу и объявлен [Latency.SLOW] с
     * самого начала; отсюда и курьёз, который правка закрывает: одна и та же фраза
     * [OCR_CLOUD_STAGE] была слышна из одного пузырька и нема из другого.
     *
     * Первый экран правка не трогает: место пузырька считается по [Latency.INSTANT] против
     * «всего остального» ([DefaultCapabilityRegistry]), и FAST с SLOW стоят по одну сторону.
     */
    override val meta = CapabilityMeta(cost = Cost.FREE, latency = Latency.SLOW)
    override fun label(state: ObjectState) = "Распознать текст"
    override fun accepts(state: ObjectState) = state.kind == ObjectKind.IMAGE
    override fun produces(state: ObjectState) = ObjectState(ObjectKind.TEXT)

    companion object { val ID = CapabilityId("ocr") }
}

/**
 * On-device OCR — the chain's **preferred** realizer (local, priority 10, always
 * selectable). Runs Tesseract (rus+eng): free, offline, no key or quota. A blank
 * recognition (hard scan) or an engine failure is a **recoverable** Failure, which the
 * Resolver's fallback chain hands off to [CloudOcrRealizer]. A hit is a TEXT object, so
 * it chains into translate / to-PDF / save.
 */
class DeviceOcrRealizer @Inject constructor(
    private val store: ObjectStore,
    private val recognizer: TextRecognizer,
) : Realizer {
    override val capabilityId = OcrCapability.ID
    override val meta = RealizerMeta(priority = 10, kind = RealizerKind.LOCAL)

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        withContext(Dispatchers.IO) {
            // The OCR enricher may have read this image already (#64) — reuse its sidecar
            // instead of running the engine a second time on the same pixels.
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
            // Стадия — только когда движок действительно запускается (#288): у обогащённой
            // картинки текст уже лежит в сайдкаре, и путь выше возвращается мгновенно; сказать
            // там «Читаю текст на устройстве» значило бы назвать работу, которой не было.
            reportStage("Читаю текст на устройстве")
            val text = runCatching { recognizer.recognize(input) }.getOrDefault("")
            if (text.isBlank() || looksLikeOcrGarbage(text)) {
                // Blank OR gibberish (Tesseract on a photographed document) → hand off to the
                // cloud vision OCR, which reads real-world photos far better.
                return@withContext ActionResult.Failure("На устройстве текст не распознан", recoverable = true)
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

/**
 * Внешний глаз в цепочке «Распознать текст» — между устройством и общей цепочкой моделей (#280).
 *
 * **Почему именно сюда.** Замер 04.08.2026 (`docs/VISION-MODELS.md`): на настоящих кадрах владельца,
 * где телефонный движок не дал текста вовсе (водомер, две накладные), специальная ручка чтения
 * прочитала всё; на эталонной ведомости — 24 строки из 24 дословно, включая кадр под углом, в тени
 * и при плохом свете. Ставить её после общей цепочки моделей значило бы держать лучший измеренный
 * способ чтения в запасе у худшего.
 *
 * Порядок цепочки: устройство (10, бесплатно и офлайн) → внешний глаз (50) → общая цепочка
 * моделей (90). Первое звено — всегда местное: наружу уходит только то, что телефон не взял.
 */
class ExternalEyeOcrRealizer @Inject constructor(
    private val eye: ExternalEye,
    private val store: ObjectStore,
) : Realizer {
    override val capabilityId = OcrCapability.ID
    override val meta = RealizerMeta(priority = 50, kind = RealizerKind.CLOUD)

    /** Здесь узнать дёшево — в отличие от [LlmClient], который прячет своих за цепочкой. */
    override fun isAvailable(): Boolean = eye.available()

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        withContext(Dispatchers.IO) { readWithExternalEye(eye, store, input) }
}

/**
 * Cloud OCR — the chain's **fallback** realizer (network vision via [LlmClient],
 * priority 90). Reached only after on-device recognised nothing. A missing key /
 * provider failure surfaces as a recoverable Failure — as the last link in the chain,
 * this is what the user sees when nothing could read the image.
 */
class CloudOcrRealizer @Inject constructor(
    private val llm: LlmClient,
    private val privacy: CloudPrivacySettings,
) : Realizer {
    override val capabilityId = OcrCapability.ID
    override val meta = RealizerMeta(priority = 90, kind = RealizerKind.CLOUD)

    /** Уровень «только Европа» и «только на телефоне» выключают общую цепочку: см. [AI_CHAIN_PRIVACY]. */
    override fun isAvailable(): Boolean = allowedAt(privacy.level(), AI_CHAIN_PRIVACY)

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        withContext(Dispatchers.IO) {
            if (!isAvailable()) return@withContext ActionResult.Failure(chainClosed(privacy.level()), recoverable = true)
            // Стадия говорит про СЕЙЧАС, а не про прошлое (#288). Соблазн был написать «на
            // устройстве не вышло — читаю в облаке»: сегодня это правда, потому что сюда попадают
            // только после отказа локального звена. Но правда о чужом шаге держится на порядке
            // цепочки в чужом файле — уедет он, и строка начнёт врать ровно тем способом, против
            // которого весь срез. Переход и так виден: строка сменилась с «на устройстве».
            reportStage(OCR_CLOUD_STAGE)
            runCatching { guarded(llm.run(input, OCR_CLOUD_PROMPT), "модель") }
                .getOrElse { ActionResult.Failure(it.message ?: "Ошибка распознавания в облаке", recoverable = true) }
        }
}

/**
 * Explicit cloud OCR — the escalation for a hard document photo where on-device Tesseract returns
 * gibberish (phone photos: low contrast, shadows, skew, handwriting). A separate, always-cloud
 * bubble the user taps when "Распознать текст" produced junk; the vision LLM reads such photos far
 * better. Distinct capability (not the fallback realizer) so it shows as its own choice.
 */
class CloudOcrCapability @Inject constructor() : Capability {
    override val id = ID
    override val icon = "ocr-cloud"
    override val meta = CapabilityMeta(cost = Cost.PAID, latency = Latency.SLOW, network = true, auth = true)
    // «В облаке» — слово наше, не человека, и рядом с обычным «Распознать текст» оно ничего не
    // объясняло: оба действия обещали «вернёт текст», и выбрать между ними было нечем. Прогон по
    // экранам 04.08.2026. Название теперь говорит, ЧЕМ оно отличается — тем, что читает сильнее,
    // а цена (объект уходит наружу) названа подписью через [yields].
    override fun label(state: ObjectState) = "Прочитать сильнее"
    override fun accepts(state: ObjectState) = state.kind == ObjectKind.IMAGE
    override fun produces(state: ObjectState) = ObjectState(ObjectKind.TEXT)

    // Цена сказана рядом с обещанием: соседнее «Распознать текст» отдаёт тот же текст, но не
    // выпускает снимок с телефона. Без этой приписки два действия обещали одно и то же слово,
    // и выбор между ними человек делал вслепую.
    override fun yields(state: ObjectState) = ActionYield.New(ObjectKind.TEXT, "текст · снимок уйдёт в сервис")

    companion object { val ID = CapabilityId("ocr-cloud") }
}

/**
 * «Распознать в облаке» — **первым читает внешний глаз** (priority 5), и только если он не дошёл,
 * ход переходит общей цепочке моделей (priority 10).
 *
 * Пузырёк тот же, подпись та же, UI не тронут: новое звено встало за существующий шов
 * «несколько Realizer на одну Capability», который `Resolver` и так умеет выстраивать в цепочку.
 */
class ExternalEyeCloudOcrRealizer @Inject constructor(
    private val eye: ExternalEye,
    private val store: ObjectStore,
) : Realizer {
    override val capabilityId = CloudOcrCapability.ID
    override val meta = RealizerMeta(priority = 5, kind = RealizerKind.CLOUD)

    override fun isAvailable(): Boolean = eye.available()

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        withContext(Dispatchers.IO) { readWithExternalEye(eye, store, input) }
}

class CloudOcrDirectRealizer @Inject constructor(
    private val llm: LlmClient,
    private val privacy: CloudPrivacySettings,
) : Realizer {
    override val capabilityId = CloudOcrCapability.ID
    override val meta = RealizerMeta(priority = 10, kind = RealizerKind.CLOUD)

    override fun isAvailable(): Boolean = allowedAt(privacy.level(), AI_CHAIN_PRIVACY)

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        withContext(Dispatchers.IO) {
            if (!isAvailable()) return@withContext ActionResult.Failure(chainClosed(privacy.level()), recoverable = true)
            reportStage(OCR_CLOUD_STAGE)
            runCatching { guarded(llm.run(input, OCR_CLOUD_PROMPT), "модель") }
                .getOrElse { ActionResult.Failure(it.message ?: "Ошибка распознавания в облаке", recoverable = true) }
        }
}

/**
 * Чтение внешним глазом целиком: спросить, проверить сторожем, положить рядом происхождение.
 *
 * Общее для обоих реализаторов, потому что работа одна: разница между ними только в том, из какого
 * пузырька в неё попадают.
 */
private suspend fun readWithExternalEye(
    eye: ExternalEye,
    store: ObjectStore,
    input: PointObject,
): ActionResult {
    reportStage(OCR_CLOUD_STAGE)
    val reading = runCatching { eye.read(input) }.getOrElse {
        return ActionResult.Failure(it.message ?: "Прочитать снаружи не удалось", recoverable = true)
    }
    degeneratedReading(reading.text)?.let { why ->
        return ActionResult.Failure(unreadable(reading.reader, why), recoverable = true)
    }
    return runCatching {
        val ref = store.newScratchFile("txt")
        File(ref.value).writeText(reading.text)
        ActionResult.Success(
            ResultObject(
                ObjectKind.TEXT,
                "text/plain",
                ref,
                buildMap {
                    put("op", "ocr")
                    // Происхождение значения видно человеку: не «облако», а кто именно и откуда.
                    put("engine", reading.reader)
                    put("where", reading.where)
                    // «Куда» без «что там с ним делают» — половина правды (#493).
                    reading.promise.takeIf { it.isNotBlank() }?.let { put("promise", it) }
                    input.metadata[META_READING_MODE]?.let { put(META_READING_MODE, it) }
                    // Сомнение едет вместе с текстом, а не тонет в нём (#425). Отказом оно не
                    // становится: выбросить накладную из-за одной подозрительной ячейки хуже, чем
                    // показать её с пометкой. Но и промолчать нельзя — на замере уверенная ошибка
                    // модели ловилась только несошедшимся итогом.
                    readingDoubts(reading.text).takeIf { it.isNotEmpty() }?.let { doubts ->
                        put(META_READING_DOUBT, doubts.joinToString("; ") { it.what })
                    }
                    // Кадр внешнему глазу готовит тот же шов, что и офлайновому движку
                    // (`OutboundFrames`), а значит мелкий кадр уехал увеличенным (#273) — то же
                    // происхождение результата, что и режим чтения, и переносится оно так же.
                    //
                    // Переносится **наблюдённое**, а не пересчитанное: множитель тут тот, который
                    // назвало чтение на устройстве. Сам внешний глаз своего множителя пока не
                    // возвращает (у чтения без геометрии нет слоя, где ему ехать), поэтому
                    // отсутствие ключа означает «мы этого не наблюдали», а не «кадр не увеличивали».
                    input.metadata[META_READ_UPSCALE]?.let { put(META_READ_UPSCALE, it) }
                },
            ),
        )
    }.getOrElse { ActionResult.Failure(it.message ?: "Ошибка записи результата", recoverable = true) }
}

/**
 * Сторож между моделью и человеком на пути общей цепочки (#280).
 *
 * Ответ модели уже лежит файлом, поэтому проверять приходится его содержимое. Файл не прочитался —
 * пропускаем: выдумывать сбой там, где его не видно, так же нечестно, как прятать настоящий.
 */
private fun guarded(result: ResultObject, who: String): ActionResult {
    val text = runCatching { File(result.uri.value).readText() }.getOrNull() ?: return ActionResult.Success(result)
    val why = degeneratedReading(text) ?: return ActionResult.Success(result)
    return ActionResult.Failure(unreadable(who, why), recoverable = true)
}

/**
 * Отказ, который человек поймёт: **«не смог прочитать»**, а не сочинённый текст.
 *
 * Причина названа, потому что она подсказывает, что делать: зацикливание и пустой ответ лечатся
 * пересъёмкой, а не повтором того же кадра. Замер поймал худший случай: одна выдуманная строка 71
 * раз подряд, с обычной уверенностью и без единого слова о том, что модель не читает.
 */
private fun unreadable(who: String, why: String): String =
    "Не смог прочитать этот снимок: $who отдала бессмыслицу ($why). " +
        "Лучше переснять при ровном свете и поближе."

/** Почему облако молчит, когда его выключил сам человек, — статус, а не поломка. */
private fun chainClosed(level: PrivacyLevel): String = when (level) {
    PrivacyLevel.DEVICE_ONLY -> "Наружу ничего не отправляется — в настройках выбрано «Только на телефоне»"
    else -> "Читают только те, кто обещал не учиться на присланном, — так выбрано в настройке " +
        "«Куда можно отправлять»"
}

/**
 * Метаданные результата распознавания. Режим чтения (#263) **переносится на текстовый объект**:
 * его наблюдал движок на картинке, а действует он дальше по цепочке — «В Word+» решает по нему,
 * помечать ли цифры (#267). Без переноса пометка не срабатывала именно там, где нужна: на
 * рукописи, распознанной отдельным шагом (ревью).
 *
 * Тем же переносом едет и увеличение кадра (#273): текст получен не с того кадра, что лежит в
 * файле, а с увеличенной копии, и это происхождение результата — ровно такое же, как «читала
 * рукопись». Оставь мы его на картинке, дальше по цепочке ответ выглядел бы прочитанным с
 * исходника.
 */
private fun ocrMeta(input: PointObject): Map<String, String> = buildMap {
    put("op", "ocr")
    put("engine", "on-device")
    input.metadata[META_READING_MODE]?.let { put(META_READING_MODE, it) }
    input.metadata[META_READ_UPSCALE]?.let { put(META_READ_UPSCALE, it) }
}
