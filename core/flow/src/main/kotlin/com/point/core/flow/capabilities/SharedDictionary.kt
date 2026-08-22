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
 * [signedIn] — есть ли аккаунт Point на этом устройстве (#1022). Спрашивается в момент
 * вопроса, а не запоминается: человек входит и выходит, не перезапуская Point.
 */
fun sharedCapabilities(
    office: OfficeOrgan = OfficeAlwaysHere,
    signedIn: () -> Boolean = { true },
): List<Capability> = listOf(
    OcrCapability(),
    QrCapability(),
    ArchiveCapability(),
    OfficeCapability(),
    ImageCapability(),
    DropLinkCapability(signedIn),
    PdfCapability(office),
)
