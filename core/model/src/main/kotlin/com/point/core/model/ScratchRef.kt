package com.point.core.model

/**
 * A local reference — a path inside the private scratch store, and today's sole
 * [ObjectRef] implementation.
 *
 * Deliberately NOT an `android.net.Uri`: keeping it a plain value keeps :core:model
 * (and the whole domain) free of Android types and trivially unit-testable. The data
 * layer maps [value] to a real file. Future schemes (content://, point://, icg://)
 * are other [ObjectRef] implementations, not extensions of this one.
 */
@JvmInline
value class ScratchRef(override val value: String) : ObjectRef
