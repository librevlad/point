package com.point.core.flow

/**
 * What kind of *document* this is — «посылка», «CMR», «чек» (#222, шаг 5).
 *
 * **Not a kind and not a role.** A document type is a semantic tag on the object, exactly as
 * `SEMANTIC_TYPES` already models «встреча»/«покупка». The rule from [com.point.core.model.ObjectKind]
 * says a kind names a thing in the world; «посылка» names a *piece of paper about* things, so it
 * cannot be one. Without this distinction every new document would demand a new kind, and there
 * are hundreds of documents.
 *
 * **Why this is a second map instead of more entries in [SEMANTIC_TYPES].** That map is closed on
 * purpose: each entry lights a [com.point.core.model.Feature], and a feature exists only when some
 * capability reacts to it. A document type earns its keep differently — it changes what the object
 * is *called*. «Посылка» instead of «Изображение» is worth having on its own, before anything can
 * act on it. Splitting the two keeps the closed map honest: nothing here invents a feature that no
 * capability accepts.
 *
 * Recognition is a **rule on device** — no key, no quota, no signal, and never on the critical path
 * of the first screen. When the classifier lands (#222, шаг 6) it writes the same tag through the
 * same validation; nothing downstream changes.
 */

/** A parcel: a delivery notification or tracking screen. */
const val TYPE_PARCEL = "parcel"

/** Document type → the word the object is called by. Open: a new document is one line here,
 *  plus its signature below. Adding one costs no feature, no capability and no kind. */
val DOCUMENT_TYPES: Map<String, String> = mapOf(
    TYPE_PARCEL to "Посылка",
)

/** Every tag the app is willing to store in `semantic.type` — from either map. A classifier's
 *  answer is checked against this, so a model can never invent a tag (#222: no free text). */
val KNOWN_SEMANTIC_TAGS: Set<String> = SEMANTIC_TYPES.keys + DOCUMENT_TYPES.keys

/** The word a document type is called by, or null when the tag means nothing to this build —
 *  an object tagged by a newer version must not end up with a blank headline. */
fun documentLabel(type: String?): String? = type?.let { DOCUMENT_TYPES[it] }

/**
 * Recognises the document type of already-read text, or null when nothing matches.
 *
 * Deliberately narrow: it says «посылка» only when the vocabulary of delivery appears twice, or
 * once alongside a waybill-shaped number. A screenshot that merely mentions «отделение» is not a
 * parcel, and calling it one would be a worse lie than «Изображение» — a wrong name is harder to
 * ignore than a boring one.
 */
fun documentType(text: String): String? {
    val hay = text.lowercase()
    val hits = PARCEL_MARKERS.count { it in hay }
    val parcel = hits >= 2 || (hits >= 1 && waybillNumbers(text).isNotEmpty())
    return if (parcel) TYPE_PARCEL else null
}

/** The vocabulary of delivery, as it appears on the carriers' own screens. Ukrainian and Russian
 *  side by side because both show up on one phone. Latin forms catch the app's English locale. */
private val PARCEL_MARKERS = listOf(
    "відділення", "отделение",
    "нова пошта", "новая почта", "nova poshta", "novaposhta",
    "укрпошта", "укрпочта", "ukrposhta",
    "посилка", "посылка", "parcel",
    "накладна", "накладная",
    "зберігання до", "хранение до",
    "отримувач", "получатель",
    "експрес-накладна", "экспресс-накладная",
)
