package com.point.data

import com.point.core.flow.EnrichCost
import com.point.core.flow.Entity
import com.point.core.flow.EntityExtractor
import com.point.core.flow.EntityType
import com.point.core.flow.META_ENTITY_PREFIX
import com.point.core.flow.META_OCR_TEXT_REF
import com.point.core.flow.ObjectStore
import com.point.core.flow.TextRecognizer
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

    private fun recognizer(text: String, fail: Boolean = false) = object : TextRecognizer {
        override suspend fun recognize(obj: PointObject): String {
            if (fail) error("engine crashed")
            return text
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
        override suspend fun children(collection: PointObject) = emptyList<PointObject>()
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

    @Test
    fun `discards gibberish and writes nothing`() = runTest {
        val store = FakeStore()
        val garbage = "; i= © © - O = & E =. are © = E oS 2 (a9) ous © E pa ae Pl ans BS &§ я OE в > 3EE:"
        val enricher = OcrEnricher(store, recognizer(garbage), extractor(Entity(EntityType.PHONE, "x")))
        val delta = enricher.enrich(image)

        assertTrue(delta.features.isEmpty())
        assertTrue(delta.metadata.isEmpty())
        assertTrue(store.created.isEmpty())
    }

    @Test
    fun `blank recognition yields nothing`() = runTest {
        val enricher = OcrEnricher(FakeStore(), recognizer("   "), extractor())
        val delta = enricher.enrich(image)
        assertTrue(delta.features.isEmpty() && delta.metadata.isEmpty())
    }

    @Test
    fun `an engine failure yields nothing`() = runTest {
        val enricher = OcrEnricher(FakeStore(), recognizer("", fail = true), extractor())
        val delta = enricher.enrich(image)
        assertTrue(delta.features.isEmpty() && delta.metadata.isEmpty())
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
