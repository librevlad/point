package com.point.executors

import com.point.core.flow.META_OCR_TEXT_REF
import com.point.core.model.ObjectKind
import com.point.core.model.PointObject

/**
 * Чем объект представляется модели, когда весь нужный текст уже лежит в запросе (#1244).
 *
 * Перевод, список покупок и отклик кладут прочитанное прямо в prompt — снимок или PDF рядом
 * с ним не нужен: наружу уходит не больше, чем требуется (принцип, выведенный в #1244; сама
 * Конституция §11 говорит о другом — о согласии на выход объекта за границу устройств). Цена
 * лишнего вложения не только в байтах: цепочка сортирует и отбрасывает исполнителей по mime,
 * и на снимке текстовые бесплатные модели, которые перевели бы мгновенно, не пробуются вовсе.
 *
 * Объект остаётся тем же — подменяется только его представление: mime текста и ссылка на
 * слой OCR, которая текстом объекта не является. Текстовый объект отдаётся как есть: у него
 * файл и есть тот самый текст.
 */
internal fun textStandIn(input: PointObject): PointObject =
    if (input.state.kind == ObjectKind.TEXT) input
    else input.copy(mime = "text/plain", metadata = input.metadata - META_OCR_TEXT_REF)
