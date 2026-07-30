package com.point.core.model

/**
 * An object whose content IS its value — a waybill number, a date, an organization name (#222).
 *
 * Extraction produces things that were never files: they were read off a page and live as a short
 * string. [ObjectRef] was written for exactly this («the reference must outlive any single
 * scheme»), so a value gets a ref of its own instead of a fake path.
 *
 * **The one rule that comes with it.** Until now every ref was a [ScratchRef] and byte access was
 * a plain `File(ref.value)`. That is no longer universally true: `File(ValueRef("20 4514 …"))` is
 * a path that does not exist. Code that dereferences a ref as a file must either be reached only
 * by file-backed objects (which is the case for every capability today — extracted objects are
 * not yet routed into them) or check the ref type first.
 */
@JvmInline
value class ValueRef(override val value: String) : ObjectRef
