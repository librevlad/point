package com.point.core.model

/**
 * A cheap, progressively-discovered signal about an object's content.
 *
 * Zero-cost features (from MIME / extension / size) are known before the first
 * render; richer ones (e.g. [ZIP_OF_IMAGES], [HAS_URL]) are computed
 * asynchronously and *augment* the bubble set later (progressive disclosure).
 */
enum class Feature {
    IS_IMAGE_PDF,
    ZIP_OF_IMAGES,
    HAS_URL,

    /** Actionable entities discovered in text (on-device) — each lights up a targeted action. */
    HAS_PHONE,
    HAS_EMAIL,
    HAS_ADDRESS,
    HAS_DATE,
    HAS_CARD,

    /** The text is a vCard (.vcf contact) — offer "add to contacts", not raw-text actions. */
    HAS_VCARD,

    /** The image contains a QR code — offer to read it (found by async peek). */
    HAS_QR,

    /**
     * В таблице прочитан период (#224): столбец дат, идущих день за днём без пропусков.
     *
     * Не «в документе есть дата», а «документ ведётся за период» — то же различие, которым
     * якорное поле схемы отделяет документ ПРО действие от документа, где действие лишь
     * упомянуто. Признак зажигает «Продлить на новый период»; без него действия нет вовсе,
     * потому что продлевать неизвестно что — значит выдумывать.
     */
    HAS_PERIOD,

    /** The text is a `point-pc://` pairing payload (#147) — offer to connect the PC. */
    HAS_PC_PAIRING,

    /** An OCR sidecar with real recognised text exists on this IMAGE (#64) — the gate for
     *  text-hungry actions (deep-understand) on a picture. Set by the OCR enricher. */
    HAS_TEXT,

    /**
     * У объекта есть слой слов с геометрией (#257) — прочитанное лежит на странице, а не только
     * строкой текста. Это гейт для действий, которые **показывают место**: поиск по документу
     * (#279) подсвечивает находки там, где они напечатаны.
     *
     * Отдельный признак от [HAS_TEXT], а не его синоним. [HAS_TEXT] загорается, только когда
     * прочитанное прошло гейт мусора, — а слой сохраняется всегда, потому что он улика (#257).
     * Обратное тоже верно: у текстового файла [HAS_TEXT] есть, а страницы под ним нет, и
     * подсвечивать находку не на чем.
     */
    HAS_WORD_LAYER,

    /**
     * The semantic level (#89): what the text IS, not what's in it. Lit from stored
     * `semantic.type` metadata — today written by study («Понять глубже», #87), tomorrow
     * possibly by on-device heuristics. Each unlocks type-specific actions.
     */
    IS_MEETING,
    IS_PURCHASE,
    IS_RECIPE,
    IS_JOB,

    /** Size above the async-enrichment threshold (e.g. a 200 MB zip). */
    LARGE,
}
