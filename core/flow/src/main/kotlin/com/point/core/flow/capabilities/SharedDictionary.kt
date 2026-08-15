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
 * [readsFramesHere] — умеет ли это устройство читать кадр само (#1021). Способность общая,
 * а обещание — про то, что произойдёт здесь: у компьютера своего чтения нет, и обещать ему
 * шаг на телефоне нельзя.
 */
fun sharedCapabilities(
    office: OfficeOrgan = OfficeAlwaysHere,
    readsFramesHere: Boolean = true,
): List<Capability> = listOf(
    OcrCapability(readsFramesHere),
    QrCapability(),
    ArchiveCapability(),
    OfficeCapability(),
    ImageCapability(),
    DropLinkCapability(),
    PdfCapability(office),
)
