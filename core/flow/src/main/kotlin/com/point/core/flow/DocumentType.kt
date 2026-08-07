package com.point.core.flow

const val TYPE_PARCEL = "parcel"

val DOCUMENT_TYPES: Map<String, String> = mapOf(
    TYPE_PARCEL to "Посылка",
)

val KNOWN_SEMANTIC_TAGS: Set<String> = SEMANTIC_TYPES.keys + DOCUMENT_TYPES.keys

fun documentLabel(type: String?): String? = type?.let { DOCUMENT_TYPES[it] }

fun documentType(text: String): String? {
    val hay = foldOcr(text)
    val hits = FOLDED_PARCEL_MARKERS.count { it in hay }
    val parcel = hits >= 2 || (hits >= 1 && waybillNumbers(text).isNotEmpty())
    return if (parcel) TYPE_PARCEL else null
}

internal fun foldOcr(s: String): String =
    s.lowercase().replace("і", "").replace("i", "").replace("ї", "")

private val PARCEL_MARKERS = listOf(
    "відділення", "отделение",
    "нова пошта", "новая почта", "nova poshta", "novaposhta",
    "укрпошта", "укрпочта", "ukrposhta",
    "посилк", "посылк", "parcel",
    "накладна", "накладная",
    "зберігання", "хранение",
    "отримувач", "одержувач", "получатель",
    "відправник", "відправлення", "отправитель",
    "місце доставки", "переадресувати", "прибула",
)

private val FOLDED_PARCEL_MARKERS = PARCEL_MARKERS.map(::foldOcr)
