package com.point.core.flow

import com.point.core.model.Feature
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import java.io.File

/**
 * Один вопрос на все текстовые исследования: несёт ли объект текст, который читают правила
 * (#1444). Текстовый файл — да; документ, запись или PDF после чтения — да (слова легли в
 * `ocr.text.ref`); картинка — нет, её слова живут атомами под Focus.
 *
 * Предикат общий нарочно: сущности и идентификаторы (суммы, счета, трек-номера) обязаны искать
 * в одних и тех же объектах. #1410 расширил сущности до документов, а идентификаторы остались на
 * голом тексте — и «Сумма к оплате: 7 800 грн» из акта не находилась, хотя дата и телефон рядом
 * находились. Вторая копия правила разошлась бы с первой так же молча.
 */
fun readsText(state: ObjectState): Boolean =
    state.kind == ObjectKind.TEXT || (state.kind != ObjectKind.IMAGE && state.has(Feature.HAS_TEXT))

/**
 * Текст объекта для правил: у текстового файла — сам файл, у остального — сидекар чтения
 * (`ocr.text.ref`), куда «Извлечь текст», расшифровка и постраничный разбор PDF кладут слова.
 * Байты документа или записи текстом не являются и в правила не идут.
 *
 * Нет ни того, ни другого — срыв операции, а не «не нашлось»: знание о тексте было, прочитать
 * его нечем.
 */
fun investigationText(obj: PointObject): String {
    val source = if (obj.state.kind == ObjectKind.TEXT) {
        File(obj.uri.value)
    } else {
        obj.metadata[META_OCR_TEXT_REF]?.let(::File)
    }
    if (source == null || !source.isFile) ownWords(NO_TEXT_PAYLOAD)
    return source.readText()
}
