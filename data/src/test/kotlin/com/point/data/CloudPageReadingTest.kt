package com.point.data

import com.point.core.flow.Atom
import com.point.core.flow.AtomCodec
import com.point.core.flow.AtomLayer
import com.point.core.flow.Box
import com.point.core.flow.META_CLOUD_ATOMS_REF
import com.point.core.flow.META_OCR_ATOMS_REF
import com.point.core.flow.ObjectStore
import com.point.core.model.PointObject
import com.point.core.model.ResultObject
import com.point.core.model.ScratchRef
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CloudPageReadingTest {

    private val store = object : ObjectStore {
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
            ScratchRef(File.createTempFile("point-", ".$extension").apply { deleteOnExit() }.absolutePath)
        override suspend fun clear() = Unit
    }

    private fun chain(answer: () -> AtomLayer) = FallbackAtomRecognizer(
        listOf(
            object : CloudAtomRecognizer {
                override val reader = "тест"
                override val configured = true
                override suspend fun read(obj: PointObject) = answer()
            },
        ),
    )

    private val layer = AtomLayer(
        listOf(Atom("un0", "11004", Box(10f, 20f, 30f, 40f), reader = "unstructured")),
    )

    @Test
    fun `слой пишется в свой ключ и не трогает офлайновое чтение`() = runTest {
        val added = CloudPageReading(store, chain { layer }).read(pageObject)

        val alreadyThere = mapOf(META_OCR_ATOMS_REF to "/scratch/atoms.tsv")
        val merged = alreadyThere + added
        assertEquals("/scratch/atoms.tsv", merged[META_OCR_ATOMS_REF])
        assertNotNull(merged[META_CLOUD_ATOMS_REF])
        assertTrue(added.keys.none { it == META_OCR_ATOMS_REF })
    }

    @Test
    fun `сохранённый слой читается обратно тем же кодеком, вместе с происхождением атома`() = runTest {
        val added = CloudPageReading(store, chain { layer }).read(pageObject)

        val decoded = AtomCodec.decode(File(added.getValue(META_CLOUD_ATOMS_REF)).readText())
        assertEquals(listOf("un0"), decoded.atoms.map { it.id })
        assertEquals("unstructured", decoded.atoms.single().reader)
        assertEquals(10f, decoded.atoms.single().box.left, 0.01f)
    }

    @Test
    fun `отказ облака не превращается в пустой слой`() = runTest {
        val reading = CloudPageReading(store, chain { error("unstructured HTTP 402") })
        val error = runCatching { reading.read(pageObject) }.exceptionOrNull()

        assertTrue(error?.message?.contains("бесплатное чтение закончилось") == true)
    }
}
