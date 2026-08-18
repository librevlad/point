package com.point.executors

import com.point.core.flow.CalendarInserter
import com.point.core.flow.Entity
import com.point.core.flow.EntityExtractor
import com.point.core.flow.KIND_ADDRESS
import com.point.core.flow.KIND_DATE
import com.point.core.flow.KIND_PHONE
import com.point.core.flow.UrlOpener
import com.point.core.model.ActionResult
import com.point.core.model.Feature
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ValueRef
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtractedObjectActionsTest {

    private fun found(kind: ObjectKind, value: String, feature: Feature) = PointObject(
        id = "src:found",
        mime = "text/plain",
        uri = ValueRef(value),
        state = ObjectState(kind, setOf(feature)),
    )

    private val address = found(KIND_ADDRESS, "вул. Сонячна, 15", Feature.HAS_ADDRESS)
    private val phone = found(KIND_PHONE, "+380671234567", Feature.HAS_PHONE)
    private val date = found(KIND_DATE, "29.07 до 18:00", Feature.HAS_DATE)

    private val blindExtractor = object : EntityExtractor {
        override suspend fun extract(text: String): List<Entity> = emptyList()
    }

    private class RecordingOpener : UrlOpener {
        var url: String? = null
        override suspend fun open(url: String) { this.url = url }
    }

    private class RecordingCalendar : CalendarInserter {
        var title: String? = null
        var day: java.time.LocalDate? = null
        override suspend fun insertEvent(title: String, day: java.time.LocalDate?) {
            this.title = title
            this.day = day
        }
    }

    @Test
    fun `tapping a found address opens the map on it`() = runTest {
        val opener = RecordingOpener()

        val result = MapRealizer(blindExtractor, opener).perform(address, null)

        assertTrue("«Открыть на карте» на найденном адресе должно срабатывать", result is ActionResult.Done)
        assertNotNull(opener.url)
        assertTrue("в карту должен уехать сам адрес", opener.url!!.contains("%D0%A1%D0%BE%D0%BD%D1%8F%D1%87%D0%BD%D0%B0"))
    }

    @Test
    fun `tapping a found phone dials it`() = runTest {
        val opener = RecordingOpener()

        val result = CallRealizer(blindExtractor, opener).perform(phone, null)

        assertTrue(result is ActionResult.Done)
        assertEquals("tel:+380671234567", opener.url)
    }

    @Test
    fun `the value is read from the object, not rediscovered by a model`() = runTest {

        val lyingExtractor = object : EntityExtractor {
            override suspend fun extract(text: String) =
                listOf(Entity(com.point.core.flow.EntityType.ADDRESS, "совсем другой адрес"))
        }
        val opener = RecordingOpener()

        MapRealizer(lyingExtractor, opener).perform(address, null)

        assertTrue(opener.url!!.contains("15"))
        assertTrue("значение объекта должно победить", !opener.url!!.contains("другой"))
    }

    @Test
    fun `the preview shows the address before opening anything`() = runTest {
        val preview = MapRealizer(blindExtractor, RecordingOpener()).preview(address)

        assertEquals(listOf("вул. Сонячна, 15"), preview?.lines)
    }

    @Test
    fun `an event made from a found date is named after it, not «Событие»`() = runTest {
        val calendar = RecordingCalendar()

        val result = EventRealizer(calendar).perform(date, null)

        assertTrue(result is ActionResult.Done)
        assertEquals("29.07 до 18:00", calendar.title)
    }

    @Test
    fun `a file-backed object still reads its file`() = runTest {

        val f = java.io.File.createTempFile("txt", ".txt").apply {
            writeText("Позвони на +380671112233"); deleteOnExit()
        }
        val text = PointObject(
            "t", "text/plain", com.point.core.model.ScratchRef(f.absolutePath),
            ObjectState(ObjectKind.TEXT, setOf(Feature.HAS_PHONE)),
        )
        val extractor = object : EntityExtractor {
            override suspend fun extract(t: String) =
                listOf(Entity(com.point.core.flow.EntityType.PHONE, "+380671112233"))
        }
        val opener = RecordingOpener()

        CallRealizer(extractor, opener).perform(text, null)

        assertEquals("tel:+380671112233", opener.url)
    }
}
