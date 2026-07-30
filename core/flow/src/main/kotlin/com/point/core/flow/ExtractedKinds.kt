package com.point.core.flow

import com.point.core.model.ObjectKind

/**
 * World-level kinds produced by extraction (#222) — the shared vocabulary of the pipeline.
 *
 * [ObjectKind.of] is grep-able on purpose so every new kind gets a human's eye, but the ones
 * already agreed on belong in one place: a typo in `ObjectKind.of("Identifer")` would silently
 * create a second, parallel kind that nothing matches.
 *
 * **The rule these obey.** A kind names a thing that exists in the world or a mark on paper —
 * never a type of document and never a role. So `Identifier`, and never `TrackingNumber` /
 * `InvoiceNumber` / `PassportNumber`: which sort of number it is comes from its [Relation]s.
 * A real CMR carries six identifiers of six different natures on one sheet — form number,
 * invoice, MRN, two vehicle plates, wagon, commodity code — and under this rule they are six
 * `Identifier`s, not six new kinds.
 *
 * The agreed vocabulary, added here as extractors for them appear:
 * - structure — `Document`, `Page`, `Paragraph`, `Table`, `Image`, `Field`
 * - things — `Organization`, `Person`, `Address`, `GeoPlace`, `Date`, `Money`, `Quantity`,
 *   [KIND_IDENTIFIER], `Product`, `Vehicle`, `Phone`, `Email`, `Url`
 * - marks on paper — `Signature`, `Stamp`, `Barcode`
 */

/** Any number that identifies something: a waybill, an invoice number, a VIN, an article code. */
val KIND_IDENTIFIER: ObjectKind = ObjectKind.of("Identifier")

/** A place you can go to — a postal branch, a delivery address, a meeting point. */
val KIND_ADDRESS: ObjectKind = ObjectKind.of("Address")

/** A point in time: a deadline, an appointment, a storage-until date. */
val KIND_DATE: ObjectKind = ObjectKind.of("Date")

/** A number you can dial. */
val KIND_PHONE: ObjectKind = ObjectKind.of("Phone")

/** An address you can write to. */
val KIND_EMAIL: ObjectKind = ObjectKind.of("Email")

/** A link you can follow. */
val KIND_URL: ObjectKind = ObjectKind.of("Url")

/** A company, a carrier, an agency — anyone that is not a person. Which *side* it is on
 *  (sender, receiver, carrier) is a role and lives in its [com.point.core.model.Relation]s. */
val KIND_ORGANIZATION: ObjectKind = ObjectKind.of("Organization")

/** Everything extraction produces so far. A frame that already IS one of these does not get
 *  extracted from again — otherwise every tap into an address would find that same address
 *  inside itself, one level deeper, forever. */
val EXTRACTED_KINDS: Set<ObjectKind> = setOf(
    KIND_IDENTIFIER, KIND_ADDRESS, KIND_DATE, KIND_PHONE, KIND_EMAIL, KIND_URL, KIND_ORGANIZATION,
)
