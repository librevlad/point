package com.point.data

import com.point.core.flow.Atom
import com.point.core.flow.AtomCodec
import com.point.core.flow.AtomLayer
import com.point.core.flow.AtomRecognizer
import com.point.core.flow.Box
import com.point.core.flow.CollectionContent
import com.point.core.flow.EnrichCost
import com.point.core.flow.Entity
import com.point.core.flow.EntityExtractor
import com.point.core.flow.EntityType
import com.point.core.flow.FrameTransform
import com.point.core.flow.INCOMPLETE_TIMEOUT
import com.point.core.flow.META_ENTITY_PREFIX
import com.point.core.flow.META_OCR_ATOMS_REF
import com.point.core.flow.META_OCR_TEXT_REF
import com.point.core.flow.META_READ_UPSCALE
import com.point.core.flow.META_READING_MODE
import com.point.core.flow.ReadingMode
import com.point.core.flow.ObjectStore
import com.point.core.model.Feature
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ResultObject
import com.point.core.model.ScratchRef
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The screenshot-comes-alive enricher: an IMAGE is OCR'd in the background, entities are
 * flagged straight on the image, and the recognised text is kept as a sidecar so entity
 * actions (and the OCR capability itself) reuse it instead of re-running the engine.
 */
class OcrEnricherTest {

    private val image = PointObject("id", "image/png", ScratchRef("/tmp/shot.png"), ObjectState(ObjectKind.IMAGE))

    private fun recognizer(text: String, fail: Boolean = false, atoms: List<Atom> = emptyList()) =
        object : AtomRecognizer {
            override suspend fun read(obj: PointObject): AtomLayer {
                if (fail) error("engine crashed")
                return AtomLayer(atoms, readerText = text.ifBlank { null })
            }
        }

    private fun extractor(vararg entities: Entity) = object : EntityExtractor {
        override suspend fun extract(text: String) = entities.toList()
    }

    private class FakeStore : ObjectStore {
        val created = mutableListOf<File>()
        override suspend fun newScratchFile(extension: String): ScratchRef {
            val f = File.createTempFile("ocr", ".$extension").apply { deleteOnExit() }
            created += f
            return ScratchRef(f.absolutePath)
        }
        override suspend fun ingest(sourceUri: String, mime: String) = throw UnsupportedOperationException()
        override suspend fun ingestMultiple(sources: List<String>) = throw UnsupportedOperationException()
        override suspend fun put(result: ResultObject) = throw UnsupportedOperationException()
        override suspend fun children(collection: PointObject, limit: Int) = CollectionContent.empty<PointObject>()
        override suspend fun readText(obj: PointObject, limit: Int) = ""
        override suspend fun clear() {}
    }

    private val realText = "Встреча завтра в 18:00, ул. Крещатик, 12. Звони +380671234567, детали https://point.app/x"

    @Test
    fun `flags entities on the image and keeps the recognised text as a sidecar`() = runTest {
        val store = FakeStore()
        val enricher = OcrEnricher(
            store,
            recognizer(realText),
            extractor(
                Entity(EntityType.PHONE, "+380671234567"),
                Entity(EntityType.DATE_TIME, "завтра в 18:00"),
                Entity(EntityType.ADDRESS, "ул. Крещатик, 12"),
            ),
        )
        val delta = enricher.enrich(image)

        assertTrue(Feature.HAS_PHONE in delta.features)
        assertTrue(Feature.HAS_DATE in delta.features)
        assertTrue(Feature.HAS_ADDRESS in delta.features)
        val ref = delta.metadata[META_OCR_TEXT_REF]
        assertNotNull("recognised text must be kept for reuse", ref)
        assertEquals(realText, File(ref!!).readText())
    }

    @Test
    fun `flags a link found in the recognised text`() = runTest {
        val enricher = OcrEnricher(FakeStore(), recognizer(realText), extractor())
        assertTrue(Feature.HAS_URL in enricher.enrich(image).features)
    }

    @Test
    fun `keeps entity values and the found link as understood facts`() = runTest {
        val enricher = OcrEnricher(
            FakeStore(),
            recognizer(realText),
            extractor(Entity(EntityType.PHONE, "+380671234567")),
        )
        val meta = enricher.enrich(image).metadata
        assertEquals("+380671234567", meta[META_ENTITY_PREFIX + "phone"])
        assertEquals("https://point.app/x", meta[META_ENTITY_PREFIX + "url"])
    }

    /**
     * Фото счётчика и скрин карты приходят в Point картинкой, а не текстовым файлом (#262):
     * правила формы обязаны стоять именно на этом пути. Урок повторный — трек однажды уже
     * потеряли, повесив правило на TEXT-энричер, который на реальном шаринге не запускается.
     */
    @Test
    fun `показание счётчика и координаты снимаются с фотографии, а не только с текста`() = runTest {
        // Квитанция, а не голое табло: на кадре с двумя словами гейт мусора закроет текст
        // раньше правил — это его работа, и правило её не отменяет.
        val page = "Особовий рахунок 305412\n" +
            "Показання лічильника електроенергії 20842 кВт·ч\n" +
            "Точка обліку 50.4501, 30.5234"
        val enricher = OcrEnricher(FakeStore(), recognizer(page), extractor())

        val meta = enricher.enrich(image).metadata

        assertEquals("20842", meta["entity.meter"])
        assertEquals("кВт·ч", meta["entity.meter.unit"])
        assertEquals("50.4501, 30.5234", meta["entity.geo"])
    }

    /** Мусорный текст не зажигает ни фич, ни сущностей — фото еды остаётся фото. Но слой атомов,
     *  если он есть, персистится и здесь: `looksLikeOcrGarbage` ложно срабатывает на фото мониторов
     *  с настоящим текстом (кадры 07/21 корпуса #262) — гейт судит представление, не улику (#257). */
    @Test
    fun `discards gibberish from features but keeps the atom evidence`() = runTest {
        val store = FakeStore()
        val garbage = "; i= © © - O = & E =. are © = E oS 2 (a9) ous © E pa ae Pl ans BS &§ я OE в > 3EE:"
        val atom = Atom("w0", "©", Box(0f, 0f, 5f, 5f), 0.2f)
        val enricher = OcrEnricher(store, recognizer(garbage, atoms = listOf(atom)), extractor(Entity(EntityType.PHONE, "x")))
        val delta = enricher.enrich(image)

        // Ни текста, ни сущностей — но слой слов есть, и сказать об этом обязано (#279): по такой
        // странице можно искать, и «Найти в документе» появляется ровно там, где есть чему
        // прилипнуть. Гейт судит представление, а признак слоя говорит про улику.
        assertEquals(setOf(Feature.HAS_WORD_LAYER), delta.features)
        // Каша — улика рукописи (#263): слой сохранён, режим чтения назван, текста нет.
        assertEquals(setOf(META_OCR_ATOMS_REF, META_READING_MODE), delta.metadata.keys)
        assertEquals(ReadingMode.HANDWRITTEN.name, delta.metadata[META_READING_MODE])
        assertEquals(listOf(atom), AtomCodec.decode(File(delta.metadata[META_OCR_ATOMS_REF]!!).readText()).atoms)
    }

    /** Слой с геометрией — улика объекта: он уходит в scratch сайдкаром и переживает пайплайн,
     *  а не уплощается в строку в момент рождения (#257: «геометрия не доходила до продакшена»). */
    @Test
    fun `persists the atom layer as a sidecar next to the text`() = runTest {
        val store = FakeStore()
        val atoms = listOf(
            Atom("w0", "20", Box(10f, 10f, 30f, 24f), 0.9f, "tesseract", "5.3", 0),
            Atom("w1", "4514", Box(34f, 10f, 80f, 24f), 0.8f, "tesseract", "5.3", 0),
        )
        val enricher = OcrEnricher(store, recognizer(realText, atoms = atoms), extractor())
        val delta = enricher.enrich(image)

        val ref = delta.metadata[META_OCR_ATOMS_REF]
        assertNotNull("the atom layer must survive as evidence", ref)
        assertEquals(atoms, AtomCodec.decode(File(ref!!).readText()).atoms)
        assertEquals(realText, File(delta.metadata[META_OCR_TEXT_REF]!!).readText())
        // Слой есть — значит, по странице можно искать (#279).
        assertTrue(Feature.HAS_WORD_LAYER in delta.features)
    }

    /**
     * Кадр увеличивали перед чтением (#273) — это происхождение результата, и человек с метрикой
     * обязаны его видеть, как видят режим чтения. Соврать про множитель нельзя: он приходит из
     * самого слоя, им же посчитаны адреса всех слов.
     */
    @Test
    fun `увеличение кадра перед чтением видно в метаданных объекта`() = runTest {
        val enlarged = object : AtomRecognizer {
            override suspend fun read(obj: PointObject) = AtomLayer(
                listOf(Atom("w0", "11004", Box(30f, 30f, 90f, 60f), 0.9f)),
                readerText = realText,
                transform = FrameTransform(sample = 1, uprightWidth = 3000, uprightHeight = 2250, upscale = 3),
            )
        }
        val delta = OcrEnricher(FakeStore(), enlarged, extractor()).enrich(image)

        assertEquals("3", delta.metadata[META_READ_UPSCALE])
    }

    /** Кадр читали как есть — ключа нет вовсе: «увеличивали в 1 раз» человеку сказать не о чем,
     *  а метрике пустая пометка мешала бы отличить прогон с увеличением от прогона без. */
    @Test
    fun `неувеличенный кадр не оставляет пометки об увеличении`() = runTest {
        val delta = OcrEnricher(FakeStore(), recognizer(realText), extractor()).enrich(image)

        assertFalse(META_READ_UPSCALE in delta.metadata)
    }

    @Test
    fun `blank recognition yields nothing but the honest reading mode`() = runTest {
        // #263: движок отработал и слов не собрал — это улика «читать будет зрячая модель»,
        // а не молчание. Признаков и текста по-прежнему нет: мусор в фичи не превращается.
        val enricher = OcrEnricher(FakeStore(), recognizer("   "), extractor())
        val delta = enricher.enrich(image)
        assertTrue(delta.features.isEmpty())
        assertEquals(ReadingMode.HANDWRITTEN.name, delta.metadata[META_READING_MODE])
        assertEquals(setOf(META_READING_MODE), delta.metadata.keys)
    }

    @Test
    fun `an engine failure yields nothing — a crash is not handwriting`() = runTest {
        // Режим чтения — наблюдение, а не догадка: движок упал, наблюдать нечего (#263).
        val enricher = OcrEnricher(FakeStore(), recognizer("", fail = true), extractor())
        val delta = enricher.enrich(image)
        assertTrue(delta.features.isEmpty() && delta.metadata.isEmpty())
    }

    /** Предел времени — не рукопись (#262): пустота отрезанного чтения означает «не успели»,
     *  а не «движок дочитал и слов нет». Режим чтения из такого слоя был бы происхождением,
     *  которого никто не наблюдал, — та же ловушка, что и упавший движок (#263). */
    @Test
    fun `чтение, отрезанное по времени, не выдаёт режим чтения`() = runTest {
        val timedOut = object : AtomRecognizer {
            override suspend fun read(obj: PointObject) =
                AtomLayer(emptyList(), incomplete = INCOMPLETE_TIMEOUT)
        }
        val delta = OcrEnricher(FakeStore(), timedOut, extractor()).enrich(image)
        assertTrue(delta.features.isEmpty() && delta.metadata.isEmpty())
    }

    /** Частичный слой таймаута (например, проба поворота вместо дочитанного полного кадра) —
     *  всё же улика: атомы и текст персистятся, только режим чтения не приписывается. */
    @Test
    fun `частичный слой таймаута сохраняет улики, но не режим чтения`() = runTest {
        val atoms = listOf(Atom("w0", "20", Box(10f, 10f, 30f, 24f), 0.9f, "tesseract", "5.3", 0))
        val partial = object : AtomRecognizer {
            override suspend fun read(obj: PointObject) =
                AtomLayer(atoms, readerText = realText, incomplete = INCOMPLETE_TIMEOUT)
        }
        val delta = OcrEnricher(FakeStore(), partial, extractor()).enrich(image)

        assertEquals(atoms, AtomCodec.decode(File(delta.metadata[META_OCR_ATOMS_REF]!!).readText()).atoms)
        assertEquals(realText, File(delta.metadata[META_OCR_TEXT_REF]!!).readText())
        assertFalse(META_READING_MODE in delta.metadata)
    }

    @Test
    fun `applies only to images and declares slow, labelled work`() {
        val enricher = OcrEnricher(FakeStore(), recognizer(""), extractor())
        assertTrue(enricher.appliesTo(ObjectState(ObjectKind.IMAGE)))
        assertFalse(enricher.appliesTo(ObjectState(ObjectKind.TEXT)))
        assertEquals(EnrichCost.SLOW, enricher.meta.cost)
        assertFalse(enricher.meta.label.isNullOrBlank())
        assertTrue(Feature.HAS_PHONE in enricher.meta.mayYield)
    }
}
