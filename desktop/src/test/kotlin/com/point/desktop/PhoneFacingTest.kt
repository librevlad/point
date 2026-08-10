package com.point.desktop

import com.point.core.flow.advertisedActions
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #628, решение владельца — «одна способность — одна кнопка».
 *
 * Компьютер объявляет телефону свои умения. Имя, собранное приклеиванием «на ПК» к чужому
 * имени, ставило в список вторую строку на то же самое умение. Теперь имя либо совпадает с
 * телефонным (одна способность, компьютер — второй исполнитель), либо названо целиком — и
 * место в нём стоит только там, где от места зависит внешний результат (ADR-0001 §7).
 */
class PhoneFacingTest {

    private val advertised = advertisedActions(desktopCapabilities())

    @Test fun `умение, общее с телефоном, едет своим именем — без приписки об устройстве`() {

        val renamed = advertised
            .filterNot { it.id.startsWith("pc-") }
            .filter { phoneFacingLabel(it) != it.label }

        assertTrue(
            "общее умение переименовано по дороге к телефону- ${renamed.map { it.label + " → " + phoneFacingLabel(it) }}",
            renamed.isEmpty(),
        )
    }

    @Test fun `расшифровка едет телефону той же способностью, а не второй кнопкой`() {

        val ids = advertised.map { it.id }

        assertTrue("у компьютера снова своё умение расшифровки- $ids", "transcribe" in ids)
        assertTrue("осталось имя, под которым телефон её не узнает- $ids", "pc-transcribe" !in ids)
    }

    @Test fun `у каждого умения, привязанного к компьютеру, в имени названо место`() {

        val nameless = advertised
            .filter { it.id.startsWith("pc-") }
            .filterNot { phoneFacingLabel(it).contains("компьютер") || phoneFacingLabel(it).contains("ПК") }

        assertTrue(
            "телефон увидит имя, не сказавшее, где будет результат- ${nameless.map { phoneFacingLabel(it) }}",
            nameless.isEmpty(),
        )
    }
}
