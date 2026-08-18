package com.point.executors

import com.point.core.flow.CalendarInserter
import com.point.core.flow.Entity
import com.point.core.flow.EntityExtractor
import com.point.core.flow.EntityType
import com.point.core.flow.META_OCR_TEXT_REF
import com.point.core.flow.UrlOpener
import com.point.core.model.ActionResult
import com.point.core.model.Feature
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class EntityActionsTest {

    private fun obj(text: String): PointObject {
        val f = File.createTempFile("ent", ".txt").apply { writeText(text); deleteOnExit() }
        return PointObject("id", "text/plain", ScratchRef(f.absolutePath), ObjectState(ObjectKind.TEXT))
    }

    private fun extractor(vararg entities: Entity) = object : EntityExtractor {
        override suspend fun extract(text: String) = entities.toList()
    }

    private fun imageWithSidecar(ocrText: String): PointObject {
        val img = File.createTempFile("shot", ".png").apply { writeBytes(byteArrayOf(1, 2, 3)); deleteOnExit() }
        val side = File.createTempFile("ocr", ".txt").apply { writeText(ocrText); deleteOnExit() }
        return PointObject(
            "img", "image/png", ScratchRef(img.absolutePath),
            ObjectState(ObjectKind.IMAGE, setOf(Feature.HAS_PHONE)),
            metadata = mapOf(META_OCR_TEXT_REF to side.absolutePath),
        )
    }

    private class RecordingExtractor(private vararg val entities: Entity) : EntityExtractor {
        var seen: String? = null
        override suspend fun extract(text: String): List<Entity> {
            seen = text
            return entities.toList()
        }
    }

    private class FakeOpener : UrlOpener {
        var opened: String? = null
        override suspend fun open(url: String) { opened = url }
    }

    private class FakeCalendar : CalendarInserter {
        var title: String? = null
        override suspend fun insertEvent(title: String, day: java.time.LocalDate?) {
            this.title = title
            this.day = day
        }

        var day: java.time.LocalDate? = null
    }

    @Test
    fun `call opens a tel URI with only the dialable characters`() = runTest {
        val opener = FakeOpener()
        val result = CallRealizer(extractor(Entity(EntityType.PHONE, "+380 (67) 123-45-67")), opener)
            .perform(obj("звони +380 (67) 123-45-67"))
        assertTrue(result is ActionResult.Done)
        assertEquals("tel:+380671234567", opener.opened)
    }

    @Test
    fun `sms opens an smsto URI`() = runTest {
        val opener = FakeOpener()
        SmsRealizer(extractor(Entity(EntityType.PHONE, "0671234567")), opener).perform(obj("x"))
        assertEquals("smsto:0671234567", opener.opened)
    }

    @Test
    fun `email opens a mailto URI`() = runTest {
        val opener = FakeOpener()
        EmailRealizer(extractor(Entity(EntityType.EMAIL, "ivan@x.com")), opener).perform(obj("x"))
        assertEquals("mailto:ivan@x.com", opener.opened)
    }

    @Test
    fun `map opens a geo URI with the encoded address`() = runTest {
        val opener = FakeOpener()
        val address = "ул. Крещатик, 12"
        MapRealizer(extractor(Entity(EntityType.ADDRESS, address)), opener).perform(obj("x"))
        assertEquals("geo:0,0?q=" + java.net.URLEncoder.encode(address, "UTF-8"), opener.opened)
    }

    @Test
    fun `event is offered for a recognised meeting even without a parsed date (#89)`() {
        val cap = EventCapability()
        assertTrue(cap.accepts(ObjectState(ObjectKind.TEXT, setOf(Feature.IS_MEETING))))
        assertTrue(cap.accepts(ObjectState(ObjectKind.TEXT, setOf(Feature.HAS_DATE))))
        assertEquals(false, cap.accepts(ObjectState(ObjectKind.TEXT)))
    }

    @Test
    fun `event inserts a calendar entry titled from the first non-blank line`() = runTest {
        val cal = FakeCalendar()
        val result = EventRealizer(cal).perform(obj("\nВстреча с командой\nзавтра 18:00"))
        assertTrue(result is ActionResult.Done)
        assertEquals("Встреча с командой", cal.title)
    }

    @Test
    fun `map preview shows the address`() = runTest {
        val preview = MapRealizer(extractor(Entity(EntityType.ADDRESS, "ул. Крещатик, 12")), FakeOpener())
            .preview(obj("встреча ул. Крещатик, 12"))
        assertEquals(listOf("ул. Крещатик, 12"), preview?.lines)
    }

    @Test
    fun `event preview shows the title from the first line`() = runTest {
        val preview = EventRealizer(FakeCalendar()).preview(obj("Встреча с командой\nзавтра 18:00"))
        assertEquals("Создать событие", preview.title)
        assertEquals(listOf("Встреча с командой"), preview.lines)
    }

    @Test
    fun `no matching entity is a recoverable failure`() = runTest {
        val result = CallRealizer(extractor(), FakeOpener()).perform(obj("no phone here"))
        assertTrue(result is ActionResult.Failure)
        assertTrue((result as ActionResult.Failure).recoverable)
    }

    @Test
    fun `entity lookup on an image reads the OCR sidecar, not the binary`() = runTest {
        val recording = RecordingExtractor(Entity(EntityType.PHONE, "+380671234567"))
        val opener = FakeOpener()
        val result = CallRealizer(recording, opener).perform(imageWithSidecar("звони +380671234567"))

        assertTrue(result is ActionResult.Done)
        assertEquals("звони +380671234567", recording.seen)
        assertEquals("tel:+380671234567", opener.opened)
    }

    @Test
    fun `event on an image titles itself from the OCR sidecar`() = runTest {
        val cal = FakeCalendar()
        EventRealizer(cal).perform(imageWithSidecar("Встреча с командой\nзавтра 18:00"))
        assertEquals("Встреча с командой", cal.title)
    }
}
