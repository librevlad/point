package com.point.core.flow

const val TYPE_PARCEL = "parcel"

val DOCUMENT_TYPES: Map<String, String> = mapOf(
    TYPE_PARCEL to "Посылка",
)

val KNOWN_SEMANTIC_TAGS: Set<String> = SEMANTIC_TYPES.keys + DOCUMENT_TYPES.keys

fun documentLabel(type: String?): String? = type?.let { DOCUMENT_TYPES[it] }

/**
 * Вид документа — по фактам, а не по одному слову (#942).
 *
 * Текстовый файл `Накладная 88-К от 12.10.2026 · Сумма 3 400 грн · Водитель: Петренко І.М.`
 * назывался «Посылка». Ни номера отправления, ни отделения, ни отправителя с получателем в
 * нём нет: слово «накладная» в счёте на перевозку — обычное слово документа, а название
 * человек читает первым и крупнее всего.
 *
 * Тот же класс, что «✓ Штрихкод 13821702» на фотографии автомобиля (#940): догадка
 * показывается наравне с прочитанным.
 *
 * Поэтому имя вида даётся, только когда его подтверждает **факт**: найденный номер
 * отправления. Одних слов мало — их пишут и в счетах, и в договорах, и в переписке. Слова
 * остаются условием необходимым, но уже не достаточным.
 */
fun documentType(text: String): String? {
    val hay = foldOcr(text)
    val ordinary = FOLDED_ORDINARY_WORDS.count { it in hay }
    val strong = FOLDED_STRONG_WORDS.count { it in hay }

    // Номер отправления — тот самый факт: он есть у посылки и его нет у счёта.
    if (waybillNumbers(text).isNotEmpty() && ordinary + strong >= 1) return TYPE_PARCEL

    // Без номера — по устройству посылки: отделение, служба, отправитель, получатель,
    // хранение. Слово «накладная» само по себе таким устройством не является: его пишут в
    // любом документе на перевозку, и по нему счёт становился «Посылкой» (#942).
    return if (strong >= 1 && ordinary + strong >= 2) TYPE_PARCEL else null
}


internal fun foldOcr(s: String): String =
    s.lowercase().replace("і", "").replace("i", "").replace("ї", "")

/** Устройство посылки: эти слова пишут там, где посылка и правда есть. */
private val STRONG_PARCEL_WORDS = listOf(
    "відділення", "отделение",
    "нова пошта", "новая почта", "nova poshta", "novaposhta",
    "укрпошта", "укрпочта", "ukrposhta",
    "посилк", "посылк", "parcel",
    "зберігання", "хранение",
    "отримувач", "одержувач", "получатель",
    "відправник", "відправлення", "отправитель",
    "місце доставки", "переадресувати", "прибула",
)

/**
 * Обычные слова документа на перевозку. Встречаются и в счёте, и в договоре, и в переписке —
 * поэтому сами по себе видом объекта не становятся (#942).
 */
private val ORDINARY_TRANSPORT_WORDS = listOf("накладна", "накладная")

private val FOLDED_STRONG_WORDS = STRONG_PARCEL_WORDS.map(::foldOcr)

private val FOLDED_ORDINARY_WORDS = ORDINARY_TRANSPORT_WORDS.map(::foldOcr)
