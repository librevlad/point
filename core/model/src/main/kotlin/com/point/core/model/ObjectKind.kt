package com.point.core.model

/**
 * What the object IS — a *thing*, not a document type and not a role (#222).
 *
 * Open by design: adding a kind must never require touching the architecture. The eight
 * constants below are the file-level kinds the runtime has always known; extraction adds
 * world-level ones (`Organization`, `Address`, `Identifier`, `Signature`…) via [of].
 *
 * **The rule that keeps this from exploding.** A kind names a thing that exists in the world
 * or a mark on paper — never a type of document and never a role:
 * - `Organization` yes · `CMRConsignee` no (a role — it lives in a [Relation]).
 * - `Identifier` yes · `TrackingNumber`, `InvoiceNumber`, `PassportNumber` no (roles of an
 *   identifier — «which kind of number» is `issued_by`, not a new kind).
 * - «this is a CMR», «this is a parcel» is a *semantic tag of the document*
 *   (`SEMANTIC_TYPES`), not a kind.
 *
 * Verified on a real CMR: one sheet carries six identifiers of six different natures (form
 * number, invoice, MRN, two vehicle plates, wagon, commodity code). Under the rule that is
 * six [Identifier]s told apart by their relations — zero new kinds. Without it, six new
 * kinds on the very first document.
 *
 * [name] is the wire format: it is persisted (history, flow snapshot, chosen apps, pinned
 * actions) and travels to the paired PC, so it must stay stable.
 */
@JvmInline
value class ObjectKind private constructor(val name: String) {

    override fun toString(): String = name

    companion object {
        val IMAGE = ObjectKind("IMAGE")
        val TEXT = ObjectKind("TEXT")
        val PDF = ObjectKind("PDF")
        val ZIP = ObjectKind("ZIP")

        /** Office document (docx / xlsx / pptx, and legacy doc/xls/ppt). */
        val OFFICE = ObjectKind("OFFICE")
        val URL = ObjectKind("URL")

        /** A set of objects — e.g. an unpacked archive. Its [PointObject.uri] is a
         *  scratch directory; collection-level actions (save all…) operate on it. */
        val COLLECTION = ObjectKind("COLLECTION")
        val UNKNOWN = ObjectKind("UNKNOWN")

        /** The file-level kinds derived from MIME on zero-cost signals (no I/O). Extraction
         *  kinds are deliberately absent: they are open and created with [of]. */
        val entries: List<ObjectKind> =
            listOf(IMAGE, TEXT, PDF, ZIP, OFFICE, URL, COLLECTION, UNKNOWN)

        /**
         * A kind by name. Unlike the enum this replaces, an unknown name is NOT an error —
         * it is simply a kind this build has no constant for, which is what keeps persisted
         * objects readable after new extractors ship. Blank is still a programming error.
         */
        fun valueOf(name: String): ObjectKind {
            require(name.isNotBlank()) { "ObjectKind name must not be blank" }
            return entries.firstOrNull { it.name == name } ?: ObjectKind(name)
        }

        /** Mint a kind an extractor produces. Grep-able on purpose: every call site is a
         *  place where the «a kind is a thing» rule above must be checked by a human. */
        fun of(name: String): ObjectKind = valueOf(name)
    }
}
