package com.point.core.flow.capabilities

import com.point.core.flow.Capability
import com.point.core.flow.OfficeAlwaysHere
import com.point.core.flow.OfficeOrgan

/**
 * Словарь способностей, общий для телефона и компьютера.
 *
 * [office] — орган, который превращает офисный документ в PDF слайд в слайд (#403).
 * У компьютера он свой и на месте; телефон подставляет сюда орган из круга.
 *
 * [ocrPromise] — слово о дороге чтения снимка от того, кто здесь читает (#1021): у телефона
 * первым идёт свой движок, у компьютера — только сервис. Словарь своего обещания не держит,
 * иначе обещание одного устройства показывалось бы на другом как своё.
 */
fun sharedCapabilities(
    office: OfficeOrgan = OfficeAlwaysHere,
    ocrPromise: String? = null,
): List<Capability> = listOf(
    OcrCapability(ocrPromise),
    QrCapability(),
    ArchiveCapability(),
    OfficeCapability(),
    ImageCapability(),
    DropLinkCapability(),
    PdfCapability(office),
)
