package com.point.core.model

enum class Provenance(

    val wire: String,
) {

    /**
     * Откуда значение — неизвестно (#948).
     *
     * Прежде отсутствие `.src` молча означало `GIVEN` — «дано», то есть пришло вместе с
     * объектом от человека или источника. Ссылка, вычитанная OCR-ом с уличного снимка,
     * записывалась ровно так же, как введённая руками, и выглядела на экране спокойнее
     * всего: у `GIVEN` нет подписи.
     *
     * Неизвестное происхождение — это не «дано». Оно и называется отдельно, и галочки не
     * получает.
     */
    UNKNOWN("unknown"),

    GIVEN("given"),

    MODEL("model"),

    RULE("rule"),

    OCR("ocr"),

    HUMAN("human"),
}

fun provenanceOf(wire: String?): Provenance =
    Provenance.entries.firstOrNull { it.wire == wire } ?: Provenance.UNKNOWN
