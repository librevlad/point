package com.point.core.flow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Строка устройства в круге — одна на телефон и компьютер (#891).
 *
 * Телефон рисовал значок вида, компьютер — цветную точку: голубая означала компьютер,
 * фиолетовая телефон, и догадаться об этом было неоткуда. Вторая строка при этом на обоих
 * писала «это устройство · Компьютер · меньше часа назад» — про устройство, которое человек
 * держит в руках.
 */
class DeviceLineTest {

    private val now = 1_700_000_000_000L

    private fun device(kind: DeviceKind, self: Boolean, agoMs: Long) = CircleDevice(
        id = "d",
        kind = kind,
        name = "Устройство",
        lastSeenMillis = now - agoMs,
        self = self,
    )

    @Test
    fun `у этого устройства время последней связи не спрашивают`() {
        val line = deviceLine(device(DeviceKind.PC, self = true, agoMs = 40 * 60_000L), now)

        assertEquals("это устройство · " + deviceKindLabel(DeviceKind.PC), line)
    }

    @Test
    fun `у чужого устройства видно, что это и когда его видели`() {
        val line = deviceLine(device(DeviceKind.PHONE, self = false, agoMs = 3 * 60 * 60_000L), now)

        assertTrue(line, line.startsWith(deviceKindLabel(DeviceKind.PHONE)))
        assertTrue("не сказано, когда видели: $line", line.contains(lastSeenLabel(now - 3 * 60 * 60_000L, now)))
    }

    @Test
    fun `значок вида зовётся общим именем, а не цветом точки`() {
        assertEquals("phone", deviceIconKey(DeviceKind.PHONE))
        assertEquals("pc", deviceIconKey(DeviceKind.PC))
    }

    @Test
    fun `экран входа называет причину, а не только просит действие`() {
        assertTrue("не сказано про второе устройство", SIGN_IN_WHY.contains("компьютере"))
        assertTrue("не сказано, что без входа тоже можно", SIGN_IN_WHY.contains("не нужен"))
    }
}
