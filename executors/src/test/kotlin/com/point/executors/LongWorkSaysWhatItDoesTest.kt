package com.point.executors

import com.point.core.flow.capabilities.ImageCapability
import com.point.core.flow.capabilities.DropLinkCapability
import com.point.core.flow.Clipboard
import com.point.core.flow.DropLink
import com.point.core.flow.Entity
import com.point.core.flow.EntityExtractor
import com.point.core.flow.EntityType
import com.point.core.flow.Exporter
import com.point.core.flow.Latency
import com.point.core.flow.ObjectStore
import com.point.core.flow.QrReader
import com.point.core.flow.Sharer
import com.point.core.flow.Viewer
import com.point.core.flow.reportStage
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ResultObject
import com.point.core.model.ScratchRef
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LongWorkSaysWhatItDoesTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `«Дать ссылку» говорит, что везёт файл, пока он едет`() = runTest {
        val file = tmp.newFile("report.pdf").apply { writeText("страница") }
        val obj = PointObject("id", "application/pdf", ScratchRef(file.absolutePath), ObjectState(ObjectKind.PDF))
        val drop = object : DropLink {
            override suspend fun give(path: String, fileName: String, mime: String) =
                com.point.core.flow.DropOutcome.Given("https://p.nt/abc")
        }

        val heard = stagesHeard { DropLinkRealizer(store(), drop).perform(obj, null) }

        assertEquals(listOf("Загружаю файл"), heard)
    }

    @Test
    fun `«Сжать» называет чтение снимка раньше, чем возьмётся за пиксели`() = runTest {
        val image = PointObject("id", "image/jpeg", ScratchRef("/tmp/фото.jpg"), ObjectState(ObjectKind.IMAGE))

        val heard = stagesHeard { ImageRealizer(store()).perform(image, null) }

        assertEquals("Читаю изображение", heard.firstOrNull())
    }

    @Test
    fun `оба заговоривших действия объявлены небыстрыми`() {
        assertTrue("«Сжать»", ImageCapability().meta.latency != Latency.INSTANT)
        assertTrue("«Дать ссылку»", DropLinkCapability().meta.latency != Latency.INSTANT)
    }

    @Test
    fun `быстрые действия не обзавелись выдуманной стадией`() = runTest {
        val invented = mutableListOf<String>()
        suspend fun mustBeSilent(name: String, work: suspend () -> Unit) {
            val heard = stagesHeard { work() }
            if (heard.isNotEmpty()) invented += "«$name» выдумало стадию: $heard"
        }

        mustBeSilent("Скопировать") { CopyRealizer(clipboard(), com.point.core.flow.CircleClipboard.None).perform(textObject("телефон 050"), null) }
        mustBeSilent("Сохранить") { SaveRealizer(exporter()).perform(textObject("а"), null) }
        mustBeSilent("Поделиться") { ShareRealizer(sharer()).perform(textObject("а"), null) }
        mustBeSilent("Открыть") { OpenRealizer(viewer()).perform(textObject("а"), null) }

        mustBeSilent("Поделиться всем") { ShareAllRealizer(sharer()).perform(collection(), null) }

        mustBeSilent("Найти в документе") { FindRealizer().perform(textObject("а"), "накладная") }

        mustBeSilent("Собрать данные") { ExtractAllRealizer(store(), extractor()).perform(textObject("050"), null) }

        mustBeSilent("Считать QR") { ReadQrRealizer(store(), qrReader()).perform(textObject("а"), null) }

        assertTrue(invented.joinToString("\n"), invented.isEmpty())
    }

    @Test
    fun `сторож правда слышит сказанное, а не тишину`() = runTest {
        val heard = stagesHeard { reportStage("проверка слуха") }

        assertEquals(listOf("проверка слуха"), heard)
    }

    private fun store() = object : ObjectStore {
        override suspend fun ingest(sourceUri: String, mime: String) = error("unused")
        override suspend fun ingestMultiple(sources: List<String>) = error("unused")
        override suspend fun put(
            result: ResultObject,
            from: com.point.core.model.PointObject?,
            by: com.point.core.model.CapabilityId?,
        ) = error("unused")
        override suspend fun children(collection: PointObject, limit: Int) = error("unused")
        override suspend fun readText(obj: PointObject, limit: Int) = error("unused")
        override suspend fun newScratchFile(extension: String) =
            ScratchRef(tmp.newFile("out-${System.nanoTime()}.$extension").absolutePath)
        override suspend fun clear() = Unit
    }

    private fun clipboard() = object : Clipboard {
        override suspend fun copy(text: String, label: String) = Unit
    }

    private fun exporter() = object : Exporter {
        override suspend fun export(obj: PointObject) = "Downloads/файл"
    }

    private fun sharer() = object : Sharer {
        override suspend fun share(obj: PointObject) = Unit
        override suspend fun shareAll(objs: List<PointObject>) = Unit
    }

    private fun viewer() = object : Viewer {
        override suspend fun view(obj: PointObject) = Unit
    }

    private fun extractor() = object : EntityExtractor {
        override suspend fun extract(text: String) = listOf(Entity(EntityType.PHONE, "050"))
    }

    private fun qrReader() = object : QrReader {
        override suspend fun decode(imagePath: String) = "https://p.nt/qr"
    }

    private fun textObject(content: String): PointObject {
        val f = tmp.newFile("text-${System.nanoTime()}.txt").apply { writeText(content) }
        return PointObject("id", "text/plain", ScratchRef(f.absolutePath), ObjectState(ObjectKind.TEXT))
    }

    private fun collection(): PointObject {
        val dir = tmp.newFolder("collection-${System.nanoTime()}")
        File(dir, "a.txt").writeText("раз")
        return PointObject(
            "id", "inode/directory", ScratchRef(dir.absolutePath), ObjectState(ObjectKind.COLLECTION),
        )
    }
}
