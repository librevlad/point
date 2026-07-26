package com.point.data

import com.point.core.model.Feature
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Pure JVM test — java.io only, no Android. */
class VCardEnricherTest {

    private val enricher = VCardEnricher()

    private fun textObject(content: String, mime: String = "text/plain"): PointObject {
        val file = File.createTempFile("point-", ".vcf").apply {
            writeText(content)
            deleteOnExit()
        }
        return PointObject(
            id = "id",
            mime = mime,
            uri = ScratchRef(file.absolutePath),
            state = ObjectState(ObjectKind.TEXT),
        )
    }

    @Test
    fun `flags HAS_VCARD by BEGIN-VCARD head even under a generic mime`() = runTest {
        val vcard = "BEGIN:VCARD\nVERSION:3.0\nFN:Александр Лаврон\nEND:VCARD"
        val features = enricher.enrich(textObject(vcard, mime = "application/octet-stream")).features
        assertTrue(Feature.HAS_VCARD in features)
    }

    @Test
    fun `flags HAS_VCARD by mime`() = runTest {
        val features = enricher.enrich(textObject("BEGIN:VCARD", mime = "text/x-vcard")).features
        assertTrue(Feature.HAS_VCARD in features)
    }

    @Test
    fun `no flag for ordinary text`() = runTest {
        val features = enricher.enrich(textObject("просто заметка про контакты и телефоны")).features
        assertFalse(Feature.HAS_VCARD in features)
    }
}
