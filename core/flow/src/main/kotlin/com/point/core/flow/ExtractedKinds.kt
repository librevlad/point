package com.point.core.flow

import com.point.core.model.ObjectKind

val KIND_IDENTIFIER: ObjectKind = ObjectKind.of("Identifier")

val KIND_ADDRESS: ObjectKind = ObjectKind.of("Address")

val KIND_DATE: ObjectKind = ObjectKind.of("Date")

val KIND_PHONE: ObjectKind = ObjectKind.of("Phone")

val KIND_EMAIL: ObjectKind = ObjectKind.of("Email")

val KIND_URL: ObjectKind = ObjectKind.of("Url")

val KIND_ORGANIZATION: ObjectKind = ObjectKind.of("Organization")

val KIND_PERSON: ObjectKind = ObjectKind.of("Person")

/**
 * Сумма — не код и не место: в неё входят ради денег (#947).
 *
 * Своего вида ей не было, и найденная сумма счёта объектом не становилась вовсе — в неё
 * нельзя было войти, хотя ради неё человек документ и открыл.
 */
val KIND_AMOUNT: ObjectKind = ObjectKind.of("Amount")

/** Место — названное словом или координатами: и то, и другое отвечает на «где». */
val KIND_PLACE: ObjectKind = ObjectKind.of("Place")

val EXTRACTED_KINDS: Set<ObjectKind> = setOf(
    KIND_IDENTIFIER, KIND_ADDRESS, KIND_DATE, KIND_PHONE, KIND_EMAIL, KIND_URL, KIND_ORGANIZATION,
    KIND_PERSON, KIND_AMOUNT, KIND_PLACE,
)
