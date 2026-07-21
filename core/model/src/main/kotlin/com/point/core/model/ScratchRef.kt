package com.point.core.model

/**
 * Opaque, platform-neutral reference to a file inside the private scratch store.
 *
 * Deliberately NOT an `android.net.Uri`: keeping it a plain value keeps
 * :core:model (and the whole domain) free of Android types and trivially
 * unit-testable. The data layer maps a [ScratchRef] to a real file / content Uri.
 */
@JvmInline
value class ScratchRef(val value: String)
