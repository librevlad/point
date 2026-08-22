package com.point.core.flow

import com.point.core.flow.capabilities.OcrCapability
import com.point.core.flow.capabilities.sharedCapabilities
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * #1021, решение владельца — «обещание по исполнителю»: одна способность чтения, слово о
 * дороге — от того, кто на этом устройстве читает.
 *
 * Общий словарь держал телефонное «сначала на телефоне, потом спрошу про сервис», и компьютер
 * показывал его как своё. Теперь словарь дороги не знает: её называет тот, кто собирает
 * словарь для своего устройства, — и ровно это слово доходит до действия.
 */
class ReadingPromiseIsTheExecutorsWordTest {

    private val image = ObjectState(ObjectKind.IMAGE)

    @Test fun `словарь сам дороги чтения не обещает`() {

        assertNull(yieldLabel(OcrCapability().yields(image)))
    }

    @Test fun `слово исполнителя доходит до действия без правок`() {

        val word = "текст · словами того, кто читает"

        val ocr = sharedCapabilities(ocrPromise = word).first { it.id == OcrCapability.ID }

        assertEquals(word, yieldLabel(ocr.yields(image)))
    }

    @Test fun `слово меняет только вторую строку — имя и выход те же`() {

        val silent = OcrCapability()
        val spoken = OcrCapability("текст · как-то")

        assertEquals(silent.id, spoken.id)
        assertEquals(silent.label(image), spoken.label(image))
        assertEquals(silent.produces(image), spoken.produces(image))
    }
}
