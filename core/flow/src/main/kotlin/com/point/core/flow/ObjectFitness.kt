package com.point.core.flow

import com.point.core.model.Feature

/**
 * Годность — часть состояния объекта, а не отдельный тип и не проверка внутри каждого
 * исполнителя (решение владельца, #684/#685). `Feature.UNUSABLE` говорит: с содержимым
 * нечего делать. Этот факт — почему, словами для человека, а не техническим кодом.
 *
 * Ставится один раз — на первом экране для пустого файла (`ObjectClassifier`) или после
 * неудачного чтения (Discovery, предпросмотр) — и виден отовсюду: экрану, подписи действия
 * под дверью и `Resolver`'у до похода в облако. Дверь при этом не исчезает — называет
 * причину заранее, второй строкой, которой у действия обычно нет вовсе (#582).
 */
const val META_UNUSABLE_REASON = "unusable.reason"

/** Файл без единого байта — нулевой сигнал первого экрана, пустота видна до всякого чтения. */
const val EMPTY_FILE_REASON = "Файл пустой — в нём нечего читать"

/** Причина, только когда она действительно сказана — пустая строка не считается знанием. */
fun unusableReasonOf(metadata: Map<String, String>): String? =
    metadata[META_UNUSABLE_REASON]?.takeIf { it.isNotBlank() }

/**
 * Причина по всему состоянию, а не только по факту: `META_UNUSABLE_REASON` без выставленного
 * `Feature.UNUSABLE` — залежавшийся или чужой ключ, не знание о годности этого объекта.
 */
fun GraphState.unusableReason(): String? =
    if (state.has(Feature.UNUSABLE)) fact(META_UNUSABLE_REASON) else null
