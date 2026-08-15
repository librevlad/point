package com.point.desktop

import com.point.core.flow.capabilities.OcrCapability
import com.point.core.flow.yieldLabel
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Подпись говорит, что произойдёт здесь (#1021).
 *
 * На компьютере «Распознать текст» обещало «текст · сначала на телефоне, потом спрошу про
 * сервис». Своего чтения у компьютера нет, идти он может только в сервис; в закрытом режиме
 * человек читал обещание шага, которого не будет, а по тапу получал «Наружу сейчас не
 * отправляем» — то есть не происходило ни того, ни другого.
 */
class PromiseIsAboutThisDeviceTest {

    private val frame = ObjectState(ObjectKind.IMAGE)

    private fun promiseOf(capabilities: Collection<com.point.core.flow.Capability>): String? =
        capabilities.first { it.id == OcrCapability.ID }.let { yieldLabel(it.yields(frame)) }

    @Test
    fun `компьютер не обещает шага на телефоне`() {
        val said = promiseOf(desktopCapabilities())

        assertFalse("компьютер обещает чужой орган: $said", said!!.contains("на телефоне"))
        assertTrue("не сказано, куда пойдёт объект: $said", said.contains("сервис"))
    }

    @Test
    fun `телефонное обещание остаётся прежним`() {
        val said = promiseOf(com.point.core.flow.capabilities.sharedCapabilities())

        assertTrue(said!!.contains("на телефоне"))
    }
}
