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

val EXTRACTED_KINDS: Set<ObjectKind> = setOf(
    KIND_IDENTIFIER, KIND_ADDRESS, KIND_DATE, KIND_PHONE, KIND_EMAIL, KIND_URL, KIND_ORGANIZATION,
    KIND_PERSON,
)
