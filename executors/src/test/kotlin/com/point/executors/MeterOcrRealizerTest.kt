package com.point.executors

import com.point.core.flow.Box
import com.point.core.flow.Cost
import com.point.core.flow.Entitlements
import com.point.core.flow.LlmClient
import com.point.core.flow.MeterDisplayReading
import com.point.core.flow.MeterReadout
import com.point.core.flow.MeterReader
import com.point.core.flow.ObjectStore
import com.point.core.flow.TextRecognizer
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
import org.junit.Test
import java.io.File

/**
 * Табло прибора как **отдельное действие за тапом человека** (#262).
 *
 * Здесь проверяется поведение, а не распознавание: сам движок и подготовка кадра живут за
 * [MeterReader], а его чистая половина — под `MeterDisplayTest`.
 *
 * Главное, что здесь доказывается, — граница: цепочка «Распознать текст» осталась прежней
 * (страница → облако), и чтение прибора её не перехватывает. Поиск табло срабатывает на 22 кадрах
 * корпуса из 23 (логотип на квитанции, строка письма, ряд дат в ведомости, гравий), а движку в
 * этом пути разрешены только цифры — значит внутри цепочки он вернул бы `Success` с выдуманным
 * числом там, где человек просил прочитать документ.
 */
class MeterOcrRealizerTest {

    private val store = object : ObjectStore {
        override suspend fun ingest(sourceUri: String, mime: String) = error("unused")
        override suspend fun ingestMultiple(sources: List<String>) = error("unused")
        override suspend fun put(result: ResultObject) = error("unused")
        override suspend fun children(collection: PointObject) = error("unused")
        override suspend fun readText(obj: PointObject, limit: Int) = error("unused")
        override suspend fun newScratchFile(extension: String) =
            ScratchRef(File.createTempFile("point-", ".$extension").apply { deleteOnExit() }.absolutePath)
        override suspend fun clear() = Unit
    }

    private val image = PointObject("id", "image/jpeg", ScratchRef("/tmp/meter.jpg"), ObjectState(ObjectKind.IMAGE))

    private fun reader(readout: MeterReadout) = object : MeterReader {
        override suspend fun read(obj: PointObject) = readout
    }

    private fun reading(digits: String) = MeterDisplayReading(digits, Box(0f, 0f, 10f, 10f), angleDegrees = 0)

    @Test
    fun `прочитанное показание становится текстовым объектом`() = runTest {
        val readout = MeterReadout(listOf(reading("00001154")), candidates = 2)
        val result = MeterOcrRealizer(store, reader(readout)).perform(image)

        assertTrue(result is ActionResult.Success)
        val out = (result as ActionResult.Success).result
        assertEquals(ObjectKind.TEXT, out.type)
        assertEquals("00001154", File(out.uri.value).readText())
        assertEquals("meter", out.metadata["reader"])
    }

    @Test
    fun `ведущие нули барабана доезжают дословно`() = runTest {
        // Сколько разрядов значащие, знает поставщик услуги, а не Point: подсказка про «1154»
        // живёт в карточке готовности, а значение остаётся тем, что на приборе.
        val result = MeterOcrRealizer(store, reader(MeterReadout(listOf(reading("007145")), 1))).perform(image)
        assertEquals("007145", File((result as ActionResult.Success).result.uri.value).readText())
    }

    @Test
    fun `два табло на кадре — обе строки, а не выбор за человека`() = runTest {
        val readout = MeterReadout(listOf(reading("00001154"), reading("0208425")), candidates = 3)
        val result = MeterOcrRealizer(store, reader(readout)).perform(image)
        assertEquals("00001154\n0208425", File((result as ActionResult.Success).result.uri.value).readText())
    }

    @Test
    fun `табло не найдено — отказ с причиной, а не пустой текст`() = runTest {
        val result = MeterOcrRealizer(store, reader(MeterReadout.NOTHING)).perform(image)

        assertTrue(result is ActionResult.Failure)
        val failure = result as ActionResult.Failure
        assertTrue(failure.recoverable)
        assertTrue(failure.reason, failure.reason.contains("не найдено"))
    }

    @Test
    fun `нашли но не прочитали — другая причина, чем не нашли`() = runTest {
        val result = MeterOcrRealizer(store, reader(MeterReadout(emptyList(), candidates = 3))).perform(image)

        val failure = result as ActionResult.Failure
        assertTrue(failure.recoverable)
        assertTrue(failure.reason, failure.reason.contains("не читаются"))
        // Две новости — два разных текста: «кадр не про прибор» и «переснимите без блика».
        val notFound = MeterOcrRealizer(store, reader(MeterReadout.NOTHING)).perform(image) as ActionResult.Failure
        assertTrue(failure.reason != notFound.reason)
    }

    @Test
    fun `сбой читателя не глотается`() = runTest {
        val broken = object : MeterReader {
            override suspend fun read(obj: PointObject) = error("движок упал")
        }
        val result = MeterOcrRealizer(store, broken).perform(image)

        val failure = result as ActionResult.Failure
        assertTrue(failure.recoverable)
        assertTrue(failure.reason, failure.reason.contains("движок упал"))
    }

    // ── граница с цепочкой «Распознать текст» ───────────────────────────────────────────────

    private class TrackingLlm : LlmClient {
        var called = false
            private set
        override suspend fun run(obj: PointObject, prompt: String): ResultObject {
            called = true
            return ResultObject(ObjectKind.TEXT, "text/markdown", ScratchRef("/cloud.md"))
        }
    }

    /** Считает, спрашивали ли у него прибор: чтение табло не должно случаться само собой. */
    private class TrackingMeterReader(private val readout: MeterReadout) : MeterReader {
        var called = false
            private set
        override suspend fun read(obj: PointObject): MeterReadout {
            called = true
            return readout
        }
    }

    /** Настоящий боевой набор: все три реализатора и оба capability, как их связывает Hilt. */
    private fun resolver(pageText: String, meter: MeterReader, llm: LlmClient) = DefaultResolver(
        realizers = setOf(
            DeviceOcrRealizer(store, object : TextRecognizer {
                override suspend fun recognize(obj: PointObject) = pageText
            }),
            MeterOcrRealizer(store, meter),
            CloudOcrRealizer(llm),
        ),
        registry = DefaultCapabilityRegistry(
            setOf(OcrCapability(), MeterOcrCapability()),
            DefaultBubblePolicy(),
        ),
        entitlements = Entitlements { true },
    )

    @Test
    fun `движок вернул шум — читает облако, а не прибор`() = runTest {
        // Ровно этот случай и опасен: до чтения прибора очередь доходила бы там, где страницу
        // прочитать не удалось, то есть на сфотографированном документе. Поиск табло на таком
        // кадре находит место почти всегда (22 кадра корпуса из 23), а движку разрешены только
        // цифры — значит человек получил бы выдуманное число вместо своего документа.
        val llm = TrackingLlm()
        val meter = TrackingMeterReader(MeterReadout(listOf(reading("0208425")), candidates = 3))
        val result = resolver("", meter, llm).realizerFor(OcrCapability.ID).perform(image)

        assertTrue(result is ActionResult.Success)
        assertEquals("/cloud.md", (result as ActionResult.Success).result.uri.value)
        assertTrue("облако не запустилось", llm.called)
        assertFalse("чтение прибора влезло в «Распознать текст»", meter.called)
    }

    @Test
    fun `страница прочиталась — ни прибора, ни облака`() = runTest {
        val llm = TrackingLlm()
        val meter = TrackingMeterReader(MeterReadout.NOTHING)
        val result = resolver(
            "Накладная Нова Пошта 20451491549395 получатель Владислав",
            meter,
            llm,
        ).realizerFor(OcrCapability.ID).perform(image)

        assertTrue(result is ActionResult.Success)
        assertFalse("обычное чтение подменено чтением прибора", meter.called)
        assertFalse(llm.called)
    }

    @Test
    fun `показание читает тот, кого попросили — за своим тапом и без облака`() = runTest {
        val llm = TrackingLlm()
        val meter = TrackingMeterReader(MeterReadout(listOf(reading("0208425")), candidates = 3))
        val result = resolver("", meter, llm).realizerFor(MeterOcrCapability.ID).perform(image)

        assertTrue(result is ActionResult.Success)
        assertEquals("0208425", File((result as ActionResult.Success).result.uri.value).readText())
        assertTrue(meter.called)
        assertFalse("снимок прибора ушёл в облако без спроса", llm.called)
    }

    @Test
    fun `у снимка есть отдельный пузырёк показания, и он местный и бесплатный`() {
        val registry = DefaultCapabilityRegistry(
            setOf(OcrCapability(), CloudOcrCapability(), MeterOcrCapability()),
            DefaultBubblePolicy(),
        )
        val bubbles = registry.bubblesFor(ObjectState(ObjectKind.IMAGE))
        assertTrue(bubbles.map { it.title }.contains("Прочитать показание"))
        // Текстовому объекту показание не предлагается: читать нечего.
        assertFalse(
            registry.bubblesFor(ObjectState(ObjectKind.TEXT)).map { it.title }.contains("Прочитать показание"),
        )

        val meter = MeterOcrCapability()
        assertFalse("чтение прибора не должно требовать сети", meter.meta.network)
        assertEquals(Cost.FREE, meter.meta.cost)
        // Снимок с устройства не уходит — согласия на это действие не спрашивают.
        assertFalse(
            DefaultResolver(
                realizers = setOf(MeterOcrRealizer(store, reader(MeterReadout.NOTHING))),
                registry = registry,
                entitlements = Entitlements { true },
            ).leavesDevice(MeterOcrCapability.ID),
        )
    }
}
