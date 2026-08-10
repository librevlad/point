package com.point.core.model

enum class Feature {
    IS_IMAGE_PDF,
    ZIP_OF_IMAGES,
    HAS_URL,

    HAS_PHONE,
    HAS_EMAIL,
    HAS_ADDRESS,
    HAS_DATE,
    HAS_CARD,

    HAS_VCARD,

    HAS_QR,

    /** Снимок знает, когда он снят и где (#547) — это факты объекта, а не съёмочная кухня. */
    HAS_SHOT_AT,
    HAS_GEO,

    HAS_PERIOD,

    HAS_TEXT,

    HAS_WORD_LAYER,

    IS_MEETING,
    IS_PURCHASE,
    IS_RECIPE,
    IS_JOB,

    LARGE,

    /**
     * Годность — часть состояния объекта, не отдельный тип (решение владельца, #684/#685).
     * С payload нечего делать: пусто с самого начала или не открылось при попытке прочитать.
     * Причина человеку — в `META_UNUSABLE_REASON` рядом (`ObjectFitness.kt`).
     */
    UNUSABLE,
}
