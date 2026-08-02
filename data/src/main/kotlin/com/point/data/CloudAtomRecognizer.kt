package com.point.data

import com.point.core.flow.AtomRecognizer
import com.point.core.flow.Box
import com.point.core.model.PointObject

/**
 * Второй читатель страницы — из **бесплатных** сервисов, без привязки карты (#280).
 *
 * Зачем вообще второй. На эталонном кадре владельца (продовольственная ведомость: печатный бланк
 * воинской части, ~35 строк × 8 колонок, поверх печати — синяя ручка) офлайновый движок выдаёт
 * кашу вида `3}3/9I=I=I=I-(8}-I8)`. Значит структуру таблицы на таком входе читают не символьные
 * движки, а те, кто видит страницу целиком. Второе чтение не заменяет первое: оно ложится **рядом**
 * (свой ключ метаданных), потому что два независимых чтения одного кадра — это и есть сигнал, где
 * доверять, а где идти перечитывать. Затри мы первое — сравнивать стало бы не с чем.
 *
 * Деньги. Опора на бесплатное: Unstructured (~15 000 страниц/мес, ключ без карты) и LlamaParse
 * (~10 000 кредитов/мес). Azure Document Intelligence на этом шаге отпадает — он требует карту.
 * На 402 (нужна карта) и 429 (кончился лимит) цепочка идёт к следующему слою, а не покупает.
 *
 * Отличие от [AtomRecognizer] — две вещи, которых офлайновому движку знать не нужно: есть ли у
 * слоя ключ и берётся ли он вообще за этот объект. Без них «нет ключа» стало бы падением, а
 * инвариант требует обратного: **нет ключа — слой молча выпадает из цепочки**.
 */
interface CloudAtomRecognizer : AtomRecognizer {

    /** Имя ридера — оно же уезжает в [com.point.core.flow.Atom.reader] и в текст отказа. */
    val reader: String

    /**
     * Ключ есть. Ключ приезжает из `local.properties` через `BuildConfig` и **только в debug** —
     * в раздаваемой сборке поле пустое, и тогда слоя просто нет.
     */
    val configured: Boolean

    /**
     * Берётся ли слой за такой объект. Сегодня оба берутся только за изображение: PDF эти сервисы
     * читают в координатах пунктов страницы, а не пикселей кадра, и приводить их к «сырому кадру»
     * пока не к чему — это отдельный срез, а не строчка в `when`.
     */
    fun canRead(obj: PointObject): Boolean = obj.mime.startsWith("image/")
}

/**
 * Место облачного бокса в **сыром** кадре.
 *
 * Две ступени, и обе обязательны:
 *
 * 1. **Нормировка отчёта.** Сервис объявляет систему координат сам (`layout_width×layout_height` у
 *    Unstructured, `page_width×page_height` у LlamaParse) и внутри свободен ужимать страницу как
 *    хочет. Поэтому сперва отчёт приводится к тому кадру, который мы реально послали.
 * 2. **[com.point.core.flow.FrameTransform].** Послали мы уменьшенную и EXIF-довёрнутую копию —
 *    она возвращает координаты в исходный файл.
 *
 * Нулевая или отсутствующая размерность — не повод делить на ноль: считаем, что сервис ответил в
 * той же системе, в какой получил. Соврать здесь дешевле нельзя — молчаливый ноль отправил бы все
 * атомы в левый верхний угол.
 */
internal fun OutboundFrame.toRawFrame(box: Box, layoutWidth: Float, layoutHeight: Float): Box {
    val sentWidth = transform.uprightWidth.toFloat()
    val sentHeight = transform.uprightHeight.toFloat()
    val scaleX = if (layoutWidth > 0f && sentWidth > 0f) sentWidth / layoutWidth else 1f
    val scaleY = if (layoutHeight > 0f && sentHeight > 0f) sentHeight / layoutHeight else 1f
    val onSentCopy = Box(box.left * scaleX, box.top * scaleY, box.right * scaleX, box.bottom * scaleY)
    return transform.toRaw(onSentCopy)
}
