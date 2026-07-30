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
