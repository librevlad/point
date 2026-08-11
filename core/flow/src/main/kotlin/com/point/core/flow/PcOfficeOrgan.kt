package com.point.core.flow

import com.point.core.flow.capabilities.PdfCapability

/**
 * Орган «офис → PDF» ищется на компьютере из круга (#403).
 *
 * Компьютер объявляет свои умения телефону обычным списком, и «В PDF» приходит оттуда с
 * причиной недоступности, когда LibreOffice или PowerPoint на нём нет. Отсюда и различие
 * двух положений человека: компьютера нет вовсе — и компьютер есть, но офиса на нём нет.
 * Второе он может исправить сам, поэтому одним словом их называть нельзя.
 *
 * Устаревшее объявление органом не считается (#633): «офис есть» недельной давности —
 * это выдумка, а не факт.
 */
class PcOfficeOrgan(
    private val caps: PcCapsStore,
    private val links: PcLinks,
    private val clock: () -> Long = System::currentTimeMillis,
) : OfficeOrgan {

    override fun missing(): String? {
        if (links.current() == null) return NO_PC_WITH_OFFICE
        if (!capsFresh(caps.savedAt(), clock())) return NO_PC_WITH_OFFICE
        val told = caps.all().firstOrNull { it.id == PdfCapability.ID.value } ?: return NO_PC_WITH_OFFICE
        return if (told.unavailable != null) NO_OFFICE_ON_PC else null
    }
}
