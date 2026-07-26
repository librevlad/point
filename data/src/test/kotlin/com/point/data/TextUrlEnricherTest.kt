package com.point.data

import com.point.core.model.Feature
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Pure JVM test — the enricher only uses java.io + regex, no Android. */
class TextUrlEnricherTest {

    private val enricher = TextUrlEnricher()

    private fun textObject(content: String): PointObject {
        val file = File.createTempFile("point-", ".txt").apply {
            writeText(content)
            deleteOnExit()
        }
        return PointObject(
            id = "id",
            mime = "text/plain",
            uri = ScratchRef(file.absolutePath),
            state = ObjectState(ObjectKind.TEXT),
        )
    }

    @Test
    fun `flags HAS_URL when the text contains a link`() = runTest {
        val delta = enricher.enrich(textObject("смотри тут https://example.com дальше"))
        val features = delta.features
        assertEquals("https://example.com", delta.metadata[com.point.core.flow.META_ENTITY_PREFIX + "url"])
        assertTrue(Feature.HAS_URL in features)
    }

    @Test
    fun `no flag when there is no link`() = runTest {
        val features = enricher.enrich(textObject("просто текст без ссылок")).features
        assertFalse(Feature.HAS_URL in features)
    }
}
