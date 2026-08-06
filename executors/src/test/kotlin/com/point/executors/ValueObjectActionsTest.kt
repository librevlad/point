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

/**
 * What the 44 existing capabilities do when an object has no file behind it (#222).
 *
 * Two opposite obligations meet here. The actions that move **bytes** must not offer themselves
 * on a waybill number — they would open a share sheet on a file called `20 4514 9154 9395` and
 * fail. The actions keyed on **features** must offer themselves untouched: that they light up
 * on an extracted address with no new code is the entire argument for building a graph instead
 * of fifteen special handlers.
 */
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

    // --- The bytes side: these used to accept everything that was not a COLLECTION ---

    @Test
    fun `actions that move bytes do not offer themselves on a value`() {
        val byteMovers = mapOf(
            "Отправить" to ShareCapability().accepts(waybill.state),
            "Сохранить" to SaveCapability().accepts(waybill.state),
            "Открыть" to OpenCapability().accepts(waybill.state),
            "AI" to AiCapability(aiKeysReady).accepts(waybill.state),
        )

        byteMovers.forEach { (name, accepted) ->
            assertFalse("«$name» must not accept an object with no file", accepted)
        }
    }

    @Test
    fun `they still accept every real file, including an unknown one`() {
        // The fix must not cost the old behaviour: `kind != COLLECTION` meant «any file».
        listOf(ObjectKind.IMAGE, ObjectKind.PDF, ObjectKind.TEXT, ObjectKind.UNKNOWN).forEach { kind ->
            val state = ObjectState(kind)
            assertTrue("Отправить on $kind", ShareCapability().accepts(state))
            assertTrue("Сохранить on $kind", SaveCapability().accepts(state))
        }
        assertFalse("a collection is still not a file", ShareCapability().accepts(ObjectState(ObjectKind.COLLECTION)))
    }

    // --- The features side: the point of the whole migration ---

    @Test
    fun `an extracted address gets «Маршрут» from the capability already written`() {
        assertTrue(MapCapability().accepts(address.state))
    }

    @Test
    fun `an extracted deadline gets «Создать событие» from the capability already written`() {
        assertTrue(EventCapability().accepts(deadline.state))
    }

    // --- Copy: the one action that always makes sense on a value ---

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
