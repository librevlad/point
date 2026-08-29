package com.point.core.flow

import com.point.core.model.PointObject
import com.point.core.model.ScratchRef

interface Sharer {
    suspend fun share(obj: PointObject)

    suspend fun shareAll(objs: List<PointObject>)
}

interface Viewer {
    suspend fun view(obj: PointObject)
}

data class AppTarget(
    val label: String,
    val packageName: String,
    val activity: String,
    val via: String? = null,
)

interface AppLauncher {

    suspend fun handlers(obj: PointObject): List<AppTarget>

    suspend fun handlersForMime(mime: String): List<AppTarget>

    suspend fun launch(target: AppTarget, obj: PointObject)

    /**
     * Кто на этом телефоне умеет работать с номером (#466).
     *
     * Владелец: «мне часто надо взять номер и пробить, кто это… звоню я как правило по
     * WhatsApp». Список даёт система, а не мы: ни одного имени стороннего сервиса в коде
     * Point нет и быть не должно — сегодня это один сервис, завтра другой.
     */
    suspend fun handlersForPhone(phone: String): List<AppTarget> = emptyList()

    /** Открыть найденное приложение с этим номером. */
    suspend fun launchWithPhone(target: AppTarget, phone: String) = Unit
}

interface Exporter {

    suspend fun export(obj: PointObject): String
}

interface UrlOpener {
    suspend fun open(url: String)
}

interface Clipboard {
    suspend fun copy(text: String, label: String)
}

interface CalendarInserter {

    /**
     * Событие ставится на найденный день, а не на сегодня (#1035).
     *
     * Дата уже лежит в знании объекта — Point находил её, показывал человеку и ею же
     * открывал дверь события. В сам календарь она не доезжала: событие ложилось на текущий
     * день, а найденный оставался словами внутри названия. `null` — дня в знании нет.
     */
    suspend fun insertEvent(title: String, day: java.time.LocalDate? = null)
}

interface ContactInserter {
    suspend fun insertContact(contact: NewContact)
}

/**
 * Всё знание, которое Point уже прочитал о человеке, едет в карточку контакта
 * (#673/#679): имя с визитки терялось, и человек дописывал руками то, что Point знал.
 */
data class NewContact(
    val name: String? = null,
    val phone: String? = null,
    val email: String? = null,
    val address: String? = null,
    val company: String? = null,
)

interface PdfTextExtractor {
    suspend fun extractText(obj: PointObject): String
}

interface SpreadsheetWriter {

    suspend fun write(
        rows: List<List<String>>,
        candidates: Map<Pair<Int, Int>, List<String>> = emptyMap(),
    ): ScratchRef

    suspend fun write(plan: SheetPlan): ScratchRef = write(plan.rows, plan.candidates)
}

interface SpreadsheetReader {
    suspend fun readRows(obj: PointObject): List<List<String>>
}

enum class DocStyle { TITLE, HEADING, BULLET, NORMAL }

data class DocBlock(
    val text: String,
    val style: DocStyle,

    val uncertain: Boolean = false,

    val evidence: CropEvidence? = null,
)

fun uncertainInExport(text: String, mode: ReadingMode): Boolean =
    text.contains('⚠') || text.contains(STRIKE_FENCE) ||
        (mode == ReadingMode.HANDWRITTEN && text.any(Char::isDigit))

const val STRIKE_FENCE = "~~"

fun List<DocBlock>.withReadingNote(mode: ReadingMode): List<DocBlock> {
    if (mode != ReadingMode.HANDWRITTEN || isEmpty()) return this
    val note = if (any { it.uncertain }) "$HANDWRITTEN_NOTE $HANDWRITTEN_MARKS" else HANDWRITTEN_NOTE
    return listOf(DocBlock(note, DocStyle.NORMAL)) + this
}

const val HANDWRITTEN_NOTE =
    "Это рукопись: текст ниже прочитан по снимку, а не взят из файла, — ошибка чтения возможна " +
        "в любом слове. Сверьте с оригиналом."

const val HANDWRITTEN_MARKS = "Жёлтым отмечено то, что стоит проверить в первую очередь."

interface DocxWriter {

    suspend fun write(paragraphs: List<String>): ScratchRef

    suspend fun writeStyled(blocks: List<DocBlock>): ScratchRef = write(blocks.map { it.text })
}

interface QrEncoder {

    suspend fun encode(text: String): ScratchRef
}

/**
 * Отделение объекта от фона: одним движком живут три действия — «Убрать фон», «Размыть фон»
 * и «Заменить фон».
 *
 * Шов общий, поэтому и слова о сбое общие (#992): наружу выходят либо слова Point — например
 * [ENGINE_PREPARING], пока движок ещё качается, — либо ничего вовсе, и тогда отказ называет
 * само действие, которое нажал человек. Технический текст движка остаётся в журнале: он
 * по-английски, он про байты и смещения и про действие человека не говорит ничего.
 */
interface BackgroundRemover {
    suspend fun cutout(imagePath: String): ScratchRef
}

/** Что за код прочитан: от этого зависят слова на экране, и только они. */
enum class CodeKind {

    QR,

    /** Товарный или книжный код под полосками: EAN, UPC, ISBN. */
    PRODUCT,
}

data class ScannedCode(val text: String, val kind: CodeKind)

interface QrReader {

    /**
     * `null` означает ровно одно: изображение открыли и посмотрели, QR в нём нет.
     *
     * Нечитаемый файл или неоткрывшийся bitmap — исключение: «не смогли посмотреть»
     * не равно «кода нет» (ADR-0001 §9).
     */
    suspend fun decode(imagePath: String): String?

    /**
     * Тот же взгляд, но с ответом «что это за код» (#445).
     *
     * Штрихкод на упаковке — не QR, и называть его QR значит соврать человеку о том, что
     * Point увидел. Вид кода нужен именно для этого, а не для рассказа о товаре: про сам
     * товар Point не утверждает ничего.
     */
    suspend fun scan(imagePath: String): ScannedCode? =
        decode(imagePath)?.let { ScannedCode(it, CodeKind.QR) }
}

interface ImageCompositor {

    suspend fun composite(subjectPath: String, backgroundPath: String): ScratchRef

    suspend fun blur(imagePath: String): ScratchRef
}

interface PdfRasterizer {

    suspend fun rasterize(obj: PointObject): ScratchRef

    suspend fun rasterizeFirstPage(obj: PointObject): ScratchRef?
}

interface OfficeTextExtractor {
    suspend fun extractText(obj: PointObject): String

    /**
     * Части, из которых документ состоит: слайды презентации по порядку (#1105).
     *
     * Тот же орган, что и чтение текста, — читатель офисного файла один. Ответ обязателен,
     * а не подразумевается пустотой: молчаливое «слайдов нет» у читателя, который просто не
     * умеет их доставать, человек прочитал бы как «в презентации ничего нет».
     */
    suspend fun slides(obj: PointObject): List<String>
}

interface ArchiveExtractor {

    suspend fun extract(obj: PointObject): ScratchRef
}
