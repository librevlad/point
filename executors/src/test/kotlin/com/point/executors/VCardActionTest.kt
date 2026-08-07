package com.point.executors

import com.point.core.flow.Viewer
import com.point.core.model.ActionResult
import com.point.core.model.Feature
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VCardActionTest {

    private class FakeViewer : Viewer {
        var viewed: PointObject? = null
        override suspend fun view(obj: PointObject) { viewed = obj }
    }

    private fun vcardObject(mime: String = "text/x-vcard") = PointObject(
        id = "id",
        mime = mime,
        uri = ScratchRef("/tmp/contact.vcf"),
        state = ObjectState(ObjectKind.TEXT, setOf(Feature.HAS_VCARD)),
    )

    @Test
    fun `capability accepts only a vCard-flagged text object`() {
        val cap = VCardCapability()
        assertTrue(cap.accepts(ObjectState(ObjectKind.TEXT, setOf(Feature.HAS_VCARD))))
        assertFalse(cap.accepts(ObjectState(ObjectKind.TEXT)))
    }

    @Test
    fun `realizer views the card forced to the vCard mime`() = runTest {
        val viewer = FakeViewer()
        val result = VCardRealizer(viewer).perform(vcardObject(mime = "application/octet-stream"), null)
        assertTrue(result is ActionResult.Done)
        assertEquals("text/x-vcard", viewer.viewed?.mime)
    }

    @Test
    fun `vCardSummary pulls the name then the phone numbers`() {
        val vcard = "BEGIN:VCARD\nVERSION:3.0\nFN:Александр Лаврон\n" +
            "item1.TEL;waid=380972905258:+380 97 290 5258\nEND:VCARD"
        assertEquals(listOf("Александр Лаврон", "+380 97 290 5258"), vCardSummary(vcard))
    }
}
