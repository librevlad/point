package com.point.core.flow

import com.point.core.model.Provenance

fun provenanceOf(metadata: Map<String, String>, key: String): Provenance =
    com.point.core.model.provenanceOf(metadata[key + META_SOURCE_SUFFIX])

fun provenanceLabel(provenance: Provenance): String? = when (provenance) {
    Provenance.OCR -> "прочитано"
    Provenance.RULE -> "выведено правилом"
    Provenance.MODEL -> "понято по смыслу"
    Provenance.HUMAN -> "подтверждено вами"
    Provenance.GIVEN -> null
    Provenance.UNKNOWN -> null
}

/**
 * Заслужило ли знание галочку (#948).
 *
 * Галочка означает «Point это знает», и доставаться она должна только тому, у чего названо
 * происхождение: прочитано, выведено правилом, понято по смыслу, подтверждено человеком.
 * Значение неизвестно откуда показывается тише — без галочки и без подписи.
 *
 * Решение владельца 13.08.2026: «Происхождение обязательно у каждого знания», «без галочки и
 * без подписи».
 */
fun isKnownFor(provenance: Provenance): Boolean = when (provenance) {
    Provenance.UNKNOWN -> false
    Provenance.GIVEN, Provenance.OCR, Provenance.RULE, Provenance.MODEL, Provenance.HUMAN -> true
}

fun isKnownFor(metadata: Map<String, String>, key: String): Boolean =
    isKnownFor(provenanceOf(metadata, key))

fun factKeyOf(metadata: Map<String, String>): String? =
    metadata.keys.firstOrNull { !isAnnotationKey(it) }

fun isAssumption(metadata: Map<String, String>, key: String): Boolean {
    val judged = metadata[key + META_EVIDENCE_SUFFIX]?.split(',')?.filter { it.isNotBlank() }
    return judged != null && judged.size < CONFIRMED_CLASSES
}

fun isDisputed(metadata: Map<String, String>, key: String): Boolean {

    // Спор, разрешённый человеком, — больше не спор (RFC §19): альтернативы остаются историей.
    if (provenanceOf(metadata, key) == Provenance.HUMAN) return false
    val value = metadata[key]?.trim() ?: return false

    // Прочтение, сошедшееся с основным по существу, — не спор, а то же знание другими словами
    // (#660, #1122): «11 September 2026» и «September 11, 2026» — один день, «067…» и «+38067…»
    // — один номер. Спорят только разные факты; совпавшие по `sameFact` согласны между собой.
    return alternativesOf(metadata, key).any { it.trim() != value && !sameFact(key, value, it.trim()) }
}

fun isDoubtful(metadata: Map<String, String>): Boolean {
    val key = factKeyOf(metadata) ?: return false
    return isAssumption(metadata, key) || isDisputed(metadata, key)
}
