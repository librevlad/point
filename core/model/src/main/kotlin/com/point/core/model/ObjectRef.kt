package com.point.core.model

/**
 * A reference to an object's bytes — deliberately NOT tied to a scheme.
 *
 * Point is an environment where objects travel between capabilities and, in time,
 * between devices and execution backends. The *reference* must outlive any single
 * scheme: today it is a local [ScratchRef]; tomorrow it may be a `content://`, a
 * `point://object/…`, or an `icg://…` handle — each a new [ObjectRef] implementation.
 *
 * [value] is the opaque backing string (a local path for [ScratchRef], a URI for
 * future schemes). While every ref is a [ScratchRef], byte access is a plain
 * `File(value)`; once a non-local implementation exists, byte access must route
 * through `ObjectStore` (`:core:flow`) instead of dereferencing [value] as a path.
 *
 * Not `sealed`: future implementations may live in other modules (e.g. a data-layer
 * `ContentRef`), which a sealed hierarchy would forbid.
 */
interface ObjectRef {
    val value: String
}
