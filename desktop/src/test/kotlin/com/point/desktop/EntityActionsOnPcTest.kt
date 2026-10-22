package com.point.desktop

import com.point.core.flow.Entity
import com.point.core.flow.EntityExtractor
import com.point.core.flow.EntityType
import com.point.core.model.ActionResult
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.URI
import java.time.LocalDate

/** Письмо, карта и событие на компьютере — органами компьютера (#1379). */
class EntityActionsOnPcTest {

    @get:Rule val tmp = TemporaryFolder()

    private val opened = mutableListOf<String>()
    private val mailed = mutableListOf<String>()
    private val opener = PcUrlOpener(mail = { mailed += it.toString() }, browse = { opened += it.toString() })

    private fun found(type: EntityType, value: String) = object : EntityExtractor {
        override suspend fun extract(text: String) = listOf(Entity(type, value))
    }

    private fun text(body: String): PointObject {
        val file = tmp.newFile().apply { writeText(body) }
        return PointObject("t", "text/plain", ScratchRef(file.absolutePath), ObjectState(ObjectKind.TEXT))
    }

    @Test fun `письмо уходит почте компьютера`() = runBlocking {
        val result = com.point.core.flow.EmailRealizer(found(EntityType.EMAIL, "ivan@x.com"), opener)
            .perform(text("ivan@x.com"), null)

        assertTrue("$result", result is ActionResult.Done)
        assertEquals(listOf("mailto:ivan@x.com"), mailed)
    }

    @Test fun `карта открывается в браузере поиском адреса`() = runBlocking {
        val address = "ул. Крещатик, 12"
        com.point.core.flow.MapRealizer(found(EntityType.ADDRESS, address), opener).perform(text(address), null)

        val link = URI(opened.single())
        assertEquals("www.google.com", link.host)
        assertTrue("адрес потерялся: $link", address in java.net.URLDecoder.decode(link.rawQuery, Charsets.UTF_8))
    }

    @Test fun `событие — файл календаря на найденный день`() = runBlocking {
        var shown: File? = null
        val calendar = PcCalendar(folder = { tmp.root }, open = { shown = it })

        calendar.insertEvent("Встреча, команда", LocalDate.of(2026, 11, 3))

        val ics = shown!!.readText()
        assertTrue(ics, "DTSTART;VALUE=DATE:20261103" in ics)
        assertTrue(ics, "DTEND;VALUE=DATE:20261104" in ics)
        assertTrue("запятая не экранирована: $ics", "SUMMARY:Встреча\\, команда" in ics)
    }
}
