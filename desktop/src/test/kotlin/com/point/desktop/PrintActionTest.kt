package com.point.desktop

import com.point.core.model.ActionResult
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PrintActionTest {

    private val document = PointObject(
        "id", "application/pdf", ScratchRef("/tmp/doc.pdf"), ObjectState(ObjectKind.PDF),
    )

    private fun printer(name: String? = "HP LaserJet", onPrint: (File) -> Unit = {}) =
        object : Printer {
            override fun name() = name
            override fun print(file: File) = onPrint(file)
        }

    @Test
    fun `печатается именно тот файл, который дали`() = runTest {
        var printed: File? = null

        val result = PcPrintRealizer(printer(onPrint = { printed = it })).perform(document, null)

        assertEquals(File("/tmp/doc.pdf"), printed)
        assertTrue(result is ActionResult.Done)
    }

    @Test
    fun `сообщение называет принтер, на который ушла печать`() = runTest {
        val result = PcPrintRealizer(printer(name = "HP LaserJet")).perform(document, null) as ActionResult.Done

        assertTrue(result.message.contains("HP LaserJet"))
    }

    @Test
    fun `говорим, что произошло, а не обещаем готовую бумагу`() = runTest {

        val result = PcPrintRealizer(printer()).perform(document, null) as ActionResult.Done

        assertTrue("не сказано, что произошло: " + result.message, result.message.contains("Отправлено на печать"))
        assertTrue("не назван принтер", result.message.contains("HP"))
        assertTrue("обещали готовую бумагу", !result.message.contains("Напечатано"))
    }

    @Test
    fun `принтер сменился между тапом и печатью — отказ, а не печать в никуда`() = runTest {

        var printed = false
        val vanished = object : Printer {
            override fun name(): String? = null
            override fun print(file: File) { printed = true }
        }

        val result = PcPrintRealizer(vanished).perform(document, null)

        assertTrue(result is ActionResult.Failure)
        assertTrue("повторить можно — это не приговор", (result as ActionResult.Failure).recoverable)
        assertTrue(!printed)
    }

    @Test
    fun `принтера нет — честный отказ, а не тихая отправка в никуда`() = runTest {
        var printed = false

        val result = PcPrintRealizer(printer(name = null) { printed = true }).perform(document, null)

        assertTrue(result is ActionResult.Failure)
        assertTrue("печатать было некуда", !printed)
    }

    @Test
    fun `сбой принтера — понятный отказ, а не падение`() = runTest {
        val result = PcPrintRealizer(printer { error("принтер недоступен") }).perform(document, null)

        assertTrue(result is ActionResult.Failure)
        assertTrue((result as ActionResult.Failure).recoverable)
    }

    @Test
    fun `действие принимает любой объект — печатают что угодно`() {
        val cap = PcPrintCapability()

        assertTrue(cap.accepts(ObjectState(ObjectKind.PDF)))
        assertTrue(cap.accepts(ObjectState(ObjectKind.IMAGE)))
        assertEquals("Напечатать", cap.label(ObjectState(ObjectKind.TEXT)))
    }
}
