package com.point.executors

import com.point.core.flow.Clipboard
import com.point.core.flow.KIND_ADDRESS
import com.point.core.flow.KIND_DATE
import com.point.core.flow.KIND_IDENTIFIER
import com.point.core.model.ActionResult
import com.point.core.model.Feature
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ValueRef
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ValueObjectActionsTest {

    private fun value(kind: ObjectKind, v: String, feature: Feature? = null) = PointObject(
        id = "found",
        mime = "text/plain",
        uri = ValueRef(v),
        state = ObjectState(kind, setOfNotNull(feature)),
    )

    private val waybill = value(KIND_IDENTIFIER, "20 4514 9154 9395")
    private val address = value(KIND_ADDRESS, "Відділення №9, Київ", Feature.HAS_ADDRESS)
    private val deadline = value(KIND_DATE, "29.07 до 18:00", Feature.HAS_DATE)

    @Test
    fun `actions that move bytes do not offer themselves on a value`() {
        val byteMovers = mapOf(
            "Сохранить" to SaveCapability().accepts(waybill.state),
            "Открыть" to OpenCapability().accepts(waybill.state),
            "AI" to AiCapability(aiKeysReady).accepts(waybill.state),
        )

        byteMovers.forEach { (name, accepted) ->
            assertFalse("«$name» must not accept an object with no file", accepted)
        }
    }

    /**
     * «Поделиться» из этого списка ушло (#820, решение владельца 12.08.2026 «Всем, кроме
     * набора»). Правило писалось, когда отправить значение было нечем: наружу уходил только
     * файл. С #584 значение уходит собой — текстом, — и прятать действие стало не от чего:
     * «не могу отправить в гетконтакт» (живой прогон 12.08.2026).
     */
    @Test
    fun `у значения есть чем поделиться — оно уходит текстом, а не файлом`() {
        assertTrue(ShareCapability().accepts(waybill.state))
        assertTrue(ShareCapability().accepts(address.state))
        assertTrue(ShareCapability().accepts(deadline.state))
    }

    @Test
    fun `they still accept every real file, including an unknown one`() {

        listOf(ObjectKind.IMAGE, ObjectKind.PDF, ObjectKind.TEXT, ObjectKind.UNKNOWN).forEach { kind ->
            val state = ObjectState(kind)
            assertTrue("Отправить on $kind", ShareCapability().accepts(state))
            assertTrue("Сохранить on $kind", SaveCapability().accepts(state))
        }
        assertFalse("a collection is still not a file", ShareCapability().accepts(ObjectState(ObjectKind.COLLECTION)))
    }

    @Test
    fun `an extracted address gets «Маршрут» from the capability already written`() {
        assertTrue(MapCapability().accepts(address.state))
    }

    @Test
    fun `an extracted deadline gets «Создать событие» from the capability already written`() {
        assertTrue(EventCapability().accepts(deadline.state))
    }

    @Test
    fun `«Скопировать» accepts a value object`() {
        assertTrue(CopyCapability().accepts(waybill.state))
        assertTrue(CopyCapability().accepts(address.state))
    }

    @Test
    fun `«Скопировать» puts the value itself on the clipboard, reading no file`() = runTest {
        val clip = RecordingClipboard()

        val result = CopyRealizer(clip, com.point.core.flow.CircleClipboard.None).perform(waybill, null)

        assertTrue(result is ActionResult.Done)
        assertEquals("20 4514 9154 9395", clip.text)
    }

    private class RecordingClipboard : Clipboard {
        var text: String? = null
        override suspend fun copy(text: String, label: String) { this.text = text }
    }
}
