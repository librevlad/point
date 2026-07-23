package com.point.core.model

/**
 * The output an Executor materialises into the scratch store (e.g. a produced
 * PDF, a compressed image, or a markdown answer written to a `.md` file).
 * Wrapped by [ActionResult] and fed back into the Flow Graph as a new object.
 */
data class ResultObject(
    val type: ObjectKind,
    val mime: String,
    val uri: ObjectRef,
    val metadata: Map<String, String> = emptyMap(),
)
