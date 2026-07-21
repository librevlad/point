package com.point.core.model

/**
 * A cheap, progressively-discovered signal about an object's content.
 *
 * Zero-cost features (from MIME / extension / size) are known before the first
 * render; richer ones (e.g. [HAS_TEXT], [ZIP_OF_IMAGES]) are computed
 * asynchronously and *augment* the bubble set later (progressive disclosure).
 */
enum class Feature {
    HAS_TEXT,
    IS_IMAGE_PDF,
    ZIP_OF_IMAGES,
    HAS_URL,

    /** Size above the async-enrichment threshold (e.g. a 200 MB zip). */
    LARGE,
}
