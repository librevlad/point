package com.point.core.flow.capabilities

import com.point.core.flow.Capability
import com.point.core.model.CapabilityId

fun sharedCapabilities(): List<Capability> = listOf(
    OcrCapability(),
    QrCapability(),
    ArchiveCapability(),
    OfficeCapability(),
    ImageCapability(),
    DropLinkCapability(),
    PdfCapability(),
)

val sharedCapabilityIds: Set<CapabilityId> get() = sharedCapabilities().map { it.id }.toSet()
