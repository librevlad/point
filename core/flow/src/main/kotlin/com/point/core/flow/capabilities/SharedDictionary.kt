package com.point.core.flow.capabilities

import com.point.core.flow.Capability

fun sharedCapabilities(): List<Capability> = listOf(
    OcrCapability(),
    QrCapability(),
    ArchiveCapability(),
    OfficeCapability(),
    ImageCapability(),
    DropLinkCapability(),
    PdfCapability(),
)
