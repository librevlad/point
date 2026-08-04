package com.point.executors

import com.point.core.flow.ExternalEye
import com.point.core.flow.ExternalReading
import com.point.core.flow.LlmClient
import com.point.core.flow.ObjectStore
import com.point.core.flow.PrivacyLevel
import com.point.core.model.ActionResult
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ResultObject
import com.point.core.model.ScratchRef
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Внешний глаз как звено цепочки чтения (#280) — и сторож между ним и человеком.
 *
 * Два свойства, ради которых всё делалось:
 * - на трудном снимке человек получает **текст**, который телефон дать не смог;
 * - на выродившемся ответе человек получает **отказ**, а не сочинённый текст.
 */
class ExternalEyeOcrTest {

    @get:Rule val temp = TemporaryFolder()

    private val image = PointObject("id", "image/jpeg", ScratchRef("/tmp/page.jpg"), ObjectState(ObjectKind.IMAGE))

    private val store = object : ObjectStore {
        override suspend fun ingest(sourceUri: String, mime: String) = error("unused")
        override suspend fun ingestMultiple(sources: List<String>) = error("unused")
        override suspend fun put(result: ResultObject) = error("unused")
        override suspend fun children(collection: PointObject, limit: Int) = error("unused")
        override suspend fun readText(obj: PointObject, limit: Int) = error("unused")
        override suspend fun newScratchFile(extension: String) =
            ScratchRef(temp.newFile("out-${System.nanoTime()}.$extension").path)
        override suspend fun clear() = Unit
    }

    private fun eye(
        available: Boolean = true,
        reader: String = "mistral-ocr",
        where: String = "Mistral, Франция (ЕС)",
        answer: () -> String,
    ) = object : ExternalEye {
        override fun available() = available
        override suspend fun read(obj: PointObject) = ExternalReading(answer(), reader, where)
    }

    private fun repeated(line: String, times: Int) = List(times) { line }.joinToString("\n")

    // --- прочитанное доезжает до человека ---

    @Test
    fun `прочитанное внешним глазом становится текстовым объектом`() = runTest {
        val result = ExternalEyeOcrRealizer(eye { "Ведомость выдачи\n1 Петренко 8300,00" }, store).perform(image)

        assertTrue(result.toString(), result is ActionResult.Success)
        val out = (result as ActionResult.Success).result
        assertEquals(ObjectKind.TEXT, out.type)
        assertTrue(File(out.uri.value).readText().contains("Петренко"))
    }

    @Test
    fun `происхождение видно человеку — кто прочитал и куда уезжал кадр`() = runTest {
        val result = ExternalEyeOcrRealizer(eye { "текст страницы" }, store).perform(image)

        val meta = (result as ActionResult.Success).result.metadata
        assertEquals("mistral-ocr", meta["engine"])
        assertEquals("Mistral, Франция (ЕС)", meta["where"])
    }

    @Test
    fun `внешний глаз стоит после устройства и до общей цепочки моделей`() {
        // Порядок — это и есть решение: местное бесплатное первым, лучший измеренный читатель
        // вторым, общая цепочка моделей последней.
        val device = DeviceOcrRealizer(store, recognizerOf("")).meta.priority
        val external = ExternalEyeOcrRealizer(eye { "x" }, store).meta.priority
        val chain = CloudOcrRealizer(failingLlm(), privacyAt()).meta.priority

        assertTrue("$device < $external < $chain", device < external && external < chain)
    }

    @Test
    fun `в «Распознать в облаке» внешний глаз читает первым`() {
        assertTrue(
            ExternalEyeCloudOcrRealizer(eye { "x" }, store).meta.priority <
                CloudOcrDirectRealizer(failingLlm(), privacyAt()).meta.priority,
        )
    }

    @Test
    fun `говорит теми же словами, что и остальное облачное чтение — работа одна`() = runTest {
        val heard = stagesHeard { ExternalEyeOcrRealizer(eye { "текст" }, store).perform(image) }
        assertEquals(listOf("Читаю снимок в облаке"), heard)
    }

    // --- сторож вырождения ---

    @Test
    fun `модель зациклилась — человек видит отказ, а не сочинённый текст`() = runTest {
        val invented = "Ивановъ Петръ Сидоровичъ, крестьянинъ"
        val result = ExternalEyeOcrRealizer(eye { repeated(invented, 71) }, store).perform(image)

        assertTrue(result.toString(), result is ActionResult.Failure)
        val reason = (result as ActionResult.Failure).reason
        assertTrue(reason, reason.contains("Не смог прочитать"))
        // Главное: выдумка НЕ доехала до человека ни одной строкой.
        assertFalse(reason, reason.contains(invented))
    }

    @Test
    fun `выродившееся чтение отдаёт ход следующему звену, а не обрывает цепочку`() = runTest {
        val result = ExternalEyeOcrRealizer(eye { repeated("одна и та же строка ответа", 40) }, store).perform(image)
        assertTrue((result as ActionResult.Failure).recoverable)
    }

    @Test
    fun `пустой ответ внешнего глаза — тоже отказ`() = runTest {
        val result = ExternalEyeOcrRealizer(eye { "" }, store).perform(image)
        assertTrue(result is ActionResult.Failure)
    }

    @Test
    fun `сторож стоит и на общей цепочке моделей, а не только на внешнем глазе`() = runTest {
        val file = temp.newFile("looped.md")
        file.writeText(repeated("выдуманная строка документа", 30))
        val llm = object : LlmClient {
            override suspend fun run(obj: PointObject, prompt: String) =
                ResultObject(ObjectKind.TEXT, "text/markdown", ScratchRef(file.path))
        }

        val result = CloudOcrRealizer(llm, privacyAt()).perform(image)

        assertTrue(result.toString(), result is ActionResult.Failure)
        assertTrue((result as ActionResult.Failure).reason.contains("Не смог прочитать"))
    }

    @Test
    fun `нормальное чтение сторож пропускает`() = runTest {
        val file = temp.newFile("good.md")
        file.writeText("| № | Фамилия | Сумма |\n| 1 | Петренко | 8300,00 |\n| 2 | Іваненко | 7200,00 |")
        val llm = object : LlmClient {
            override suspend fun run(obj: PointObject, prompt: String) =
                ResultObject(ObjectKind.TEXT, "text/markdown", ScratchRef(file.path))
        }

        assertTrue(CloudOcrRealizer(llm, privacyAt()).perform(image) is ActionResult.Success)
    }

    // --- уровень приватности ---

    @Test
    fun `только на телефоне — общая цепочка моделей выпадает и честно говорит почему`() = runTest {
        val realizer = CloudOcrRealizer(failingLlm(), privacyAt(PrivacyLevel.DEVICE_ONLY))

        assertFalse(realizer.isAvailable())
        val result = realizer.perform(image)
        assertTrue((result as ActionResult.Failure).reason.contains("Только на телефоне"))
    }

    @Test
    fun `строгий уровень — общая цепочка молчит, потому что обещать за неё некому`() = runTest {
        val realizer = CloudOcrDirectRealizer(failingLlm(), privacyAt(PrivacyLevel.NO_TRAINING))

        // Внутри цепочки маршрутизаторы и тарифы разных стран, и кто ответит — решается в рантайме.
        assertFalse(realizer.isAvailable())
        assertTrue((realizer.perform(image) as ActionResult.Failure).reason.contains("не учиться на присланном"))
    }

    @Test
    fun `по умолчанию читают все — умолчание это максимум бесплатного`() {
        assertTrue(CloudOcrRealizer(failingLlm(), privacyAt()).isAvailable())
    }

    @Test
    fun `нет разрешённого глаза — звено выпадает из цепочки до запуска`() {
        assertFalse(ExternalEyeOcrRealizer(eye(available = false) { "x" }, store).isAvailable())
        assertTrue(ExternalEyeOcrRealizer(eye { "x" }, store).isAvailable())
    }

    private fun failingLlm() = object : LlmClient {
        override suspend fun run(obj: PointObject, prompt: String): ResultObject = error("нет ключа")
    }

    private fun recognizerOf(text: String) = object : com.point.core.flow.TextRecognizer {
        override suspend fun recognize(obj: PointObject) = text
    }
}
