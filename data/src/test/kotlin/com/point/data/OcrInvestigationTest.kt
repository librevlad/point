package com.point.data

import com.point.core.flow.Atom
import com.point.core.flow.AtomCodec
import com.point.core.flow.AtomLayer
import com.point.core.flow.AtomRecognizer
import com.point.core.flow.Box
import com.point.core.flow.CollectionContent
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

class OcrInvestigationTest {

    private val image = PointObject("id", "image/png", ScratchRef("/tmp/shot.png"), ObjectState(ObjectKind.IMAGE))

    private fun recognizer(
        text: String,
        fail: Boolean = false,
        atoms: List<Atom> = emptyList(),
        incomplete: String? = null,
    ) = object : AtomRecognizer {
        override suspend fun read(obj: PointObject): AtomLayer {
            if (fail) error("engine crashed")
            return AtomLayer(atoms, readerText = text.ifBlank { null }, incomplete = incomplete)
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
        override suspend fun put(
            result: ResultObject,
            from: com.point.core.model.PointObject?,
            by: com.point.core.model.CapabilityId?,
        ) = throw UnsupportedOperationException()
        override suspend fun children(collection: PointObject, limit: Int) = CollectionContent.empty<PointObject>()
        override suspend fun readText(obj: PointObject, limit: Int) = ""
        override suspend fun clear() {}
    }

    private val realText = "Встреча завтра в 18:00, ул. Крещатик, 12. Звони +380671234567, детали https://point.app/x"

    @Test
    fun `flags entities on the image and keeps the recognised text as a sidecar`() = runTest {
        val store = FakeStore()
        val enricher = OcrInvestigationRealizer(
            store,
            recognizer(realText),
            extractor(
                Entity(EntityType.PHONE, "+380671234567"),
                Entity(EntityType.DATE_TIME, "завтра в 18:00"),
                Entity(EntityType.ADDRESS, "ул. Крещатик, 12"),
            ),
        )
        val delta = enricher.look(image)

        assertTrue(Feature.HAS_PHONE in delta.features)
        assertTrue(Feature.HAS_DATE in delta.features)
        assertTrue(Feature.HAS_ADDRESS in delta.features)
        val ref = delta.metadata[META_OCR_TEXT_REF]
        assertNotNull("recognised text must be kept for reuse", ref)
        assertEquals(realText, File(ref!!).readText())
    }

    @Test
    fun `flags a link found in the recognised text`() = runTest {
        val enricher = OcrInvestigationRealizer(FakeStore(), recognizer(realText), extractor())
        assertTrue(Feature.HAS_URL in enricher.look(image).features)
    }

    @Test
    fun `keeps entity values and the found link as understood facts`() = runTest {
        val enricher = OcrInvestigationRealizer(
            FakeStore(),
            recognizer(realText),
            extractor(Entity(EntityType.PHONE, "+380671234567")),
        )
        val meta = enricher.look(image).metadata
        assertEquals("+380671234567", meta[META_ENTITY_PREFIX + "phone"])
        assertEquals("https://point.app/x", meta[META_ENTITY_PREFIX + "url"])
    }

    @Test
    fun `показание счётчика и координаты снимаются с фотографии, а не только с текста`() = runTest {

        val page = "Особовий рахунок 305412\n" +
            "Показання лічильника електроенергії 20842 кВт·ч\n" +
            "Точка обліку 50.4501, 30.5234"
        val enricher = OcrInvestigationRealizer(FakeStore(), recognizer(page), extractor())

        val meta = enricher.look(image).metadata

        assertEquals("20842", meta["entity.meter"])
        assertEquals("кВт·ч", meta["entity.meter.unit"])
        assertEquals("50.4501, 30.5234", meta["entity.geo"])
    }

    @Test
    fun `discards gibberish from features but keeps the atom evidence`() = runTest {
        val store = FakeStore()
        val garbage = "; i= © © - O = & E =. are © = E oS 2 (a9) ous © E pa ae Pl ans BS &§ я OE в > 3EE:"
        val atom = Atom("w0", "©", Box(0f, 0f, 5f, 5f), 0.2f)
        val enricher = OcrInvestigationRealizer(store, recognizer(garbage, atoms = listOf(atom)), extractor(Entity(EntityType.PHONE, "x")))
        val delta = enricher.look(image)

        assertEquals(setOf(Feature.HAS_WORD_LAYER), delta.features)

        assertEquals(setOf(META_OCR_ATOMS_REF, META_READING_MODE), delta.metadata.keys)
        assertEquals(ReadingMode.HANDWRITTEN.name, delta.metadata[META_READING_MODE])
        assertEquals(listOf(atom), AtomCodec.decode(File(delta.metadata[META_OCR_ATOMS_REF]!!).readText()).atoms)
    }

    @Test
    fun `persists the atom layer as a sidecar next to the text`() = runTest {
        val store = FakeStore()
        val atoms = listOf(
            Atom("w0", "20", Box(10f, 10f, 30f, 24f), 0.9f, "tesseract", "5.3", 0),
            Atom("w1", "4514", Box(34f, 10f, 80f, 24f), 0.8f, "tesseract", "5.3", 0),
        )
        val enricher = OcrInvestigationRealizer(store, recognizer(realText, atoms = atoms), extractor())
        val delta = enricher.look(image)

        val ref = delta.metadata[META_OCR_ATOMS_REF]
        assertNotNull("the atom layer must survive as evidence", ref)
        assertEquals(atoms, AtomCodec.decode(File(ref!!).readText()).atoms)
        assertEquals(realText, File(delta.metadata[META_OCR_TEXT_REF]!!).readText())

        assertTrue(Feature.HAS_WORD_LAYER in delta.features)
    }

    @Test
    fun `увеличение кадра перед чтением видно в метаданных объекта`() = runTest {
        val enlarged = object : AtomRecognizer {
            override suspend fun read(obj: PointObject) = AtomLayer(
                listOf(Atom("w0", "11004", Box(30f, 30f, 90f, 60f), 0.9f)),
                readerText = realText,
                transform = FrameTransform(sample = 1, uprightWidth = 3000, uprightHeight = 2250, upscale = 3),
            )
        }
        val delta = OcrInvestigationRealizer(FakeStore(), enlarged, extractor()).look(image)

        assertEquals("3", delta.metadata[META_READ_UPSCALE])
    }

    @Test
    fun `неувеличенный кадр не оставляет пометки об увеличении`() = runTest {
        val delta = OcrInvestigationRealizer(FakeStore(), recognizer(realText), extractor()).look(image)

        assertFalse(META_READ_UPSCALE in delta.metadata)
    }

    @Test
    fun `две сущности одного фото различимы и по id, и по месту на странице`() = runTest {
        val atoms = listOf(
            Atom("w1", "тел:", Box(10f, 20f, 60f, 40f), confidence = 0.99f),
            Atom("w2", "+380671234567", Box(70f, 20f, 260f, 40f), confidence = 0.99f),
            Atom("w3", "почта:", Box(10f, 120f, 80f, 140f), confidence = 0.99f),
            Atom("w4", "hello@example.org", Box(90f, 120f, 300f, 140f), confidence = 0.99f),
        )
        val enricher = OcrInvestigationRealizer(
            FakeStore(),
            recognizer("тел: +380671234567\nпочта: hello@example.org", atoms = atoms),
            extractor(
                Entity(EntityType.PHONE, "+380671234567"),
                Entity(EntityType.EMAIL, "hello@example.org"),
            ),
        )
        val delta = enricher.look(image)

        val phone = delta.objects.single { it.state.kind == com.point.core.flow.KIND_PHONE }
        val email = delta.objects.single { it.state.kind == com.point.core.flow.KIND_EMAIL }
        val phoneAt = phone.metadata[com.point.core.flow.META_AT_REGION]
        val emailAt = email.metadata[com.point.core.flow.META_AT_REGION]

        assertTrue("телефон обязан знать своё место", phoneAt != null)
        assertTrue("почта обязана знать своё место", emailAt != null)
        assertTrue("места разных сущностей различны", phoneAt != emailAt)
        assertTrue("id разных сущностей различны", phone.id != email.id)

        val box = com.point.core.flow.regionOfWire(phoneAt)!!
        assertTrue("регион телефона лежит в его строке, а не в строке почты", box.top < 120f)
    }

    @Test
    fun `значение без однозначного места остаётся без региона, а не с выдуманным`() = runTest {

        val atoms = listOf(
            Atom("w1", "+380671234567", Box(10f, 20f, 210f, 40f), confidence = 0.99f),
            Atom("w2", "+380671234567", Box(10f, 220f, 210f, 240f), confidence = 0.99f),
        )
        val enricher = OcrInvestigationRealizer(
            FakeStore(),
            recognizer("+380671234567\n+380671234567", atoms = atoms),
            extractor(Entity(EntityType.PHONE, "+380671234567")),
        )
        val delta = enricher.look(image)

        val phone = delta.objects.single { it.state.kind == com.point.core.flow.KIND_PHONE }
        assertTrue(
            "двусмысленное место честнее не называть",
            com.point.core.flow.META_AT_REGION !in phone.metadata,
        )
    }

    @Test
    fun `blank recognition yields nothing but the honest reading mode`() = runTest {

        val enricher = OcrInvestigationRealizer(FakeStore(), recognizer("   "), extractor())
        val delta = enricher.look(image)
        assertTrue(delta.features.isEmpty())
        assertEquals(ReadingMode.HANDWRITTEN.name, delta.metadata[META_READING_MODE])
        assertEquals(setOf(META_READING_MODE), delta.metadata.keys)
    }

    @Test
    fun `an engine failure is a failure, not an empty reading — a crash is not handwriting`() = runTest {

        val enricher = OcrInvestigationRealizer(FakeStore(), recognizer("", fail = true), extractor())
        val result = enricher.perform(image, null)

        assertTrue("сбой движка обязан остаться сбоем, а не пустым знанием-" + result,
            result is com.point.core.model.ActionResult.Failure)
    }

    @Test
    fun `чтение, отрезанное по времени без единого слова, — неудача, а не пустая страница`() = runTest {
        val timedOut = object : AtomRecognizer {
            override suspend fun read(obj: PointObject) =
                AtomLayer(emptyList(), incomplete = INCOMPLETE_TIMEOUT)
        }
        val result = OcrInvestigationRealizer(FakeStore(), timedOut, extractor()).perform(image, null)

        assertTrue("обрыв без находок обязан быть неудачей-" + result,
            result is com.point.core.model.ActionResult.Failure)
    }

    @Test
    fun `таймаут с уже прочитанными словами оставляет частичное знание`() = runTest {
        val atoms = listOf(Atom("w1", "+380671234567", Box(10f, 20f, 210f, 40f), confidence = 0.99f))
        val partial = recognizer("+380671234567", atoms = atoms, incomplete = INCOMPLETE_TIMEOUT)
        val delta = OcrInvestigationRealizer(FakeStore(), partial, extractor()).look(image)

        assertTrue("частичное чтение — всё ещё знание", delta.metadata.isNotEmpty())

        assertTrue("режим чтения при обрыве не угадывается",
            META_READING_MODE !in delta.metadata)
    }

    @Test
    fun `нераскрывшийся снимок — знание о негодности объекта, а не провал операции`() = runTest {

        // #684/#685: «decode failed» — это про сам объект, не про попытку сейчас. Такое
        // знание остаётся с объектом (Feature.UNUSABLE) и после этого тапа — не гаснет
        // разовым отказом, как раньше.
        val broken = recognizer("", incomplete = "decode failed")
        val delta = OcrInvestigationRealizer(FakeStore(), broken, extractor()).look(image)

        assertEquals(setOf(Feature.UNUSABLE), delta.features)

        // Причина ридера не теряется — но и не выходит к человеку чужим языком:
        // «decode failed» на экране был жаргоном (#686), теперь это только журнал.
        val reason = delta.metadata[com.point.core.flow.META_UNUSABLE_REASON]
        assertNotNull("что-то сказано", reason)
        assertTrue("что-то сказано", reason!!.isNotBlank())
        assertTrue("без латиницы платформы", reason.none { it in 'a'..'z' || it in 'A'..'Z' })
    }

    @Test
    fun `не изображение вовсе — та же негодность, что и битые байты`() = runTest {
        val notAnImage = recognizer("", incomplete = "not an image")
        val delta = OcrInvestigationRealizer(FakeStore(), notAnImage, extractor()).look(image)

        assertEquals(setOf(Feature.UNUSABLE), delta.features)
    }

    @Test
    fun `движок не завёлся — неудача операции, объект не порочится навсегда`() = runTest {

        // В отличие от битых байт — это про попытку сейчас (устройство, движок), а не про
        // содержимое: закрывать путь наружу навсегда здесь нельзя (#684/#685).
        val enginedown = recognizer("", incomplete = "engine init failed")
        val result = OcrInvestigationRealizer(FakeStore(), enginedown, extractor()).perform(image, null)

        assertTrue(result is com.point.core.model.ActionResult.Failure)
    }

    @Test
    fun `частичный слой таймаута сохраняет улики, но не режим чтения`() = runTest {
        val atoms = listOf(Atom("w0", "20", Box(10f, 10f, 30f, 24f), 0.9f, "tesseract", "5.3", 0))
        val partial = object : AtomRecognizer {
            override suspend fun read(obj: PointObject) =
                AtomLayer(atoms, readerText = realText, incomplete = INCOMPLETE_TIMEOUT)
        }
        val delta = OcrInvestigationRealizer(FakeStore(), partial, extractor()).look(image)

        assertEquals(atoms, AtomCodec.decode(File(delta.metadata[META_OCR_ATOMS_REF]!!).readText()).atoms)
        assertEquals(realText, File(delta.metadata[META_OCR_TEXT_REF]!!).readText())
        assertFalse(META_READING_MODE in delta.metadata)
    }

    @Test
    fun `applies only to images and declares slow, labelled work`() {
        val enricher = OcrInvestigationRealizer(FakeStore(), recognizer(""), extractor())
        assertTrue(OcrInvestigation().accepts(ObjectState(ObjectKind.IMAGE)))
        assertFalse(OcrInvestigation().accepts(ObjectState(ObjectKind.TEXT)))
        val declared = OcrInvestigation()
        assertEquals(com.point.core.flow.Latency.SLOW, declared.meta.latency)
        assertTrue(declared.meta.investigation)
        assertFalse(declared.label(ObjectState(ObjectKind.IMAGE)).isBlank())
        assertTrue(Feature.HAS_PHONE in declared.meta.mayYield)
    }
}
