package com.point.core.flow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Приватность — настройка у человека, а не фильтр в коде (решение владельца 04.08.2026).
 *
 * Прежде сервис, который логирует присланное, в цепочку не попадал вовсе — и это решало за
 * человека, отнимая у него ровно то, ради чего он поставил Point. Теперь выбирает он, а умолчание
 * даёт максимум бесплатного.
 */
class CloudPrivacyTest {

    private val europe = ReaderPrivacy("Mistral, Франция (ЕС)", europe = true, logsRequests = false)
    private val overseas = ReaderPrivacy("сервис в США", europe = false, logsRequests = true)

    @Test
    fun `умолчание — максимум бесплатного`() {
        assertEquals(PrivacyLevel.FREE_FIRST, PrivacyLevel.DEFAULT)
        assertEquals(PrivacyLevel.DEFAULT, PrivacyLevel.of(null))
        assertEquals(PrivacyLevel.DEFAULT, PrivacyLevel.of("уровень-которого-нет"))
    }

    @Test
    fun `по умолчанию читают все, включая тех, кто хранит присланное у себя`() {
        assertTrue(allowedAt(PrivacyLevel.FREE_FIRST, overseas))
        assertTrue(allowedAt(PrivacyLevel.FREE_FIRST, europe))
    }

    @Test
    fun `только Европа — остаются те, про кого адрес известен поимённо`() {
        assertTrue(allowedAt(PrivacyLevel.EUROPE_ONLY, europe))
        assertFalse(allowedAt(PrivacyLevel.EUROPE_ONLY, overseas))
        // Общая цепочка моделей маршрутизирует куда угодно — обещать про неё Европу нельзя.
        assertFalse(allowedAt(PrivacyLevel.EUROPE_ONLY, AI_CHAIN_PRIVACY))
    }

    @Test
    fun `только на телефоне — наружу не выпускается никто`() {
        assertFalse(allowedAt(PrivacyLevel.DEVICE_ONLY, europe))
        assertFalse(allowedAt(PrivacyLevel.DEVICE_ONLY, overseas))
        assertFalse(allowedAt(PrivacyLevel.DEVICE_ONLY, AI_CHAIN_PRIVACY))
    }

    @Test
    fun `отбор не пересобирает очередь — порядок это ранжирование по замеру`() {
        val chain = listOf("mistral" to europe, "штаты" to overseas, "ovh" to europe)
        assertEquals(
            listOf("mistral", "штаты", "ovh"),
            allowedBy(PrivacyLevel.FREE_FIRST, chain) { it.second }.map { it.first },
        )
        assertEquals(
            listOf("mistral", "ovh"),
            allowedBy(PrivacyLevel.EUROPE_ONLY, chain) { it.second }.map { it.first },
        )
        assertTrue(allowedBy(PrivacyLevel.DEVICE_ONLY, chain) { it.second }.isEmpty())
    }

    @Test
    fun `у каждого уровня человек видит и выигрыш, и цену`() {
        PrivacyLevel.entries.forEach { level ->
            assertTrue("$level без названия", level.title.isNotBlank())
            assertTrue("$level без объяснения", level.what.length > 30)
            // «Провайдер» — слово разработчика; человек выбирает, куда уходит его документ.
            assertFalse("$level говорит на языке кода: ${level.what}", level.what.contains("провайдер"))
            assertFalse("$level говорит на языке кода: ${level.title}", level.title.contains("провайдер"))
        }
    }

    @Test
    fun `настройка не отменяет согласия и говорит об этом`() {
        assertTrue(PRIVACY_SETTING_HINT.contains("тапа"))
        assertTrue(PRIVACY_SETTING_HINT.contains("куда"))
        assertTrue(PRIVACY_SETTING_TITLE.isNotBlank())
    }
}
