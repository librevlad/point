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

/**
 * «Напечатать на ПК» (#291): телефон печатать не умеет, компьютер умеет, а весь транспорт
 * между ними уже построен. Здесь проверяется пара «что» и «как» — AWT за швом, тест чистый.
 */
class PrintActionTest {

    private val document = PointObject(
        "id", "application/pdf", ScratchRef("/tmp/doc.pdf"), ObjectState(ObjectKind.PDF),
    )

    @Test
    fun `печатается именно тот файл, который дали`() = runTest {
        var printed: File? = null

        val result = PcPrintRealizer { file -> printed = file }.perform(document, null)

        assertEquals(File("/tmp/doc.pdf"), printed)
        assertTrue(result is ActionResult.Done)
    }

    @Test
    fun `обещаем отправку в очередь, а не готовую бумагу`() = runTest {
        // Бумага могла кончиться, и увидит это человек, а не мы: обещать «напечатано» нельзя.
        val result = PcPrintRealizer { }.perform(document, null) as ActionResult.Done

        assertTrue(result.message.contains("принтер"))
        assertTrue(!result.message.contains("Напечатано"))
    }

    @Test
    fun `сбой принтера — понятный отказ, а не падение`() = runTest {
        val result = PcPrintRealizer { error("принтер недоступен") }.perform(document, null)

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
