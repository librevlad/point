package com.point.executors

import com.point.core.flow.DropLink
import com.point.core.flow.NOT_SIGNED_IN_TO_GIVE_LINKS
import com.point.core.flow.ObjectStore
import com.point.core.model.ActionResult
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ResultObject
import com.point.core.model.ScratchRef
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Согласие на публикацию не спрашивают там, где публикация невозможна (#1022).
 *
 * Телефон без аккаунта предлагал «Дать ссылку», показывал экран согласия «файл уедет на
 * сервер Point и сутки будет открыт любому», получал «Выложить» — и отвечал «нет связи с
 * сервером или файл слишком большой». Обе догадки были неверны: связь была, файл — в 880 раз
 * меньше предела. Настоящая причина: устройство не вошло в аккаунт.
 */
class LinkIsNotPromisedWhereItCannotBeGivenTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun store() = object : ObjectStore {
        override suspend fun ingest(sourceUri: String, mime: String) = error("unused")
        override suspend fun ingestMultiple(sources: List<String>) = error("unused")
        override suspend fun put(result: ResultObject) = error("unused")
        override suspend fun children(collection: PointObject, limit: Int) = error("unused")
        override suspend fun readText(obj: PointObject, limit: Int) = error("unused")
        override suspend fun newScratchFile(extension: String) =
            ScratchRef(File(tmp.root, "link.$extension").absolutePath)

        override suspend fun clear() = Unit
    }

    private fun objectOf(file: File) =
        PointObject("id", "application/pdf", ScratchRef(file.absolutePath), ObjectState(ObjectKind.PDF))

    private fun drop(canGive: Boolean, link: String? = "https://p.nt/abc") = object : DropLink {
        override fun canGive() = canGive
        override suspend fun give(path: String, fileName: String, mime: String) = link
    }

    @Test
    fun `без аккаунта дверь помечена недоступной с настоящей причиной`() {
        val realizer = DropLinkRealizer(store(), drop(canGive = false))

        assertFalse(realizer.isAvailable())
        assertEquals(NOT_SIGNED_IN_TO_GIVE_LINKS, realizer.unavailableReason())
    }

    @Test
    fun `с аккаунтом действие доступно и молчит про причины`() {
        val realizer = DropLinkRealizer(store(), drop(canGive = true))

        assertTrue(realizer.isAvailable())
        assertNull(realizer.unavailableReason())
    }

    @Test
    fun `отказ не выдумывает причин, которых не знает`() = runTest {
        val file = File(tmp.root, "report.pdf").apply { writeText("страница") }

        val result = DropLinkRealizer(store(), drop(canGive = true, link = null))
            .perform(objectOf(file), null)

        val said = (result as ActionResult.Failure).reason
        assertFalse("отказ гадает про связь: $said", said.contains("связи"))
        assertFalse("отказ гадает про размер: $said", said.contains("большой"))
        assertTrue("у отказа нет выхода: $said", result.recoverable)
    }
}
