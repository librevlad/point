package com.point.core.flow

import com.point.core.model.Provenance

fun provenanceOf(metadata: Map<String, String>, key: String): Provenance =
    com.point.core.model.provenanceOf(metadata[key + META_SOURCE_SUFFIX])

fun provenanceLabel(provenance: Provenance): String? = when (provenance) {
    Provenance.OCR -> "прочитано"
    Provenance.RULE -> "выведено правилом"
    Provenance.MODEL -> "прочитано моделью"
    Provenance.HUMAN -> "подтверждено вами"
    Provenance.GIVEN -> null
}

fun factKeyOf(metadata: Map<String, String>): String? =
    metadata.keys.firstOrNull { !isAnnotationKey(it) }

fun isAssumption(metadata: Map<String, String>, key: String): Boolean {
    val judged = metadata[key + META_EVIDENCE_SUFFIX]?.split(',')?.filter { it.isNotBlank() }
    return judged != null && judged.size < CONFIRMED_CLASSES
}

fun isDisputed(metadata: Map<String, String>, key: String): Boolean {

    // Спор, разрешённый человеком, — больше не спор (RFC §19): альтернативы остаются историей.
    if (provenanceOf(metadata, key) == Provenance.HUMAN) return false
    val value = metadata[key]?.trim()
    return alternativesOf(metadata, key).any { it.trim() != value }
}

fun isDoubtful(metadata: Map<String, String>): Boolean {
    val key = factKeyOf(metadata) ?: return false
    return isAssumption(metadata, key) || isDisputed(metadata, key)
}
