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

    /** The text is a vCard (.vcf contact) — offer "add to contacts", not raw-text actions. */
    HAS_VCARD,

    /** Size above the async-enrichment threshold (e.g. a 200 MB zip). */
    LARGE,
}
