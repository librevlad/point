package com.point.core.flow.capabilities

import com.point.core.flow.Capability
import com.point.core.flow.OfficeAlwaysHere
import com.point.core.flow.OfficeOrgan

/**
 * Словарь способностей, общий для телефона и компьютера.
 *
 * [office] — орган, который превращает офисный документ в PDF слайд в слайд (#403).
 * У компьютера он свой и на месте; телефон подставляет сюда орган из круга.
 */
fun sharedCapabilities(office: OfficeOrgan = OfficeAlwaysHere): List<Capability> = listOf(
    OcrCapability(),
    QrCapability(),
    ArchiveCapability(),
    OfficeCapability(),
    ImageCapability(),
    DropLinkCapability(),
    PdfCapability(office),
)
