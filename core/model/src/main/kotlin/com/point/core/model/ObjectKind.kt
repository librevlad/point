package com.point.core.model

/**
 * Coarse object category, derived from MIME on zero-cost signals (no I/O).
 * Drives the first, generic set of bubbles; [Feature]s refine it afterwards.
 */
enum class ObjectKind {
    IMAGE,
    TEXT,
    PDF,
    ZIP,
    URL,
    UNKNOWN,
}
