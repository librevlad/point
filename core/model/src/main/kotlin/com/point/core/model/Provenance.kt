package com.point.core.model

enum class Provenance(

    val wire: String,
) {

    GIVEN("given"),

    MODEL("model"),

    RULE("rule"),

    OCR("ocr"),

    HUMAN("human"),
}

fun provenanceOf(wire: String?): Provenance =
    Provenance.entries.firstOrNull { it.wire == wire } ?: Provenance.GIVEN
