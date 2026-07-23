package com.point.data

import com.point.core.flow.Entity
import com.point.core.flow.EntityExtractor
import com.point.core.flow.EntityType
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

/** The enricher maps on-device entities to features; ML Kit itself is faked (pure JVM). */
class EntityEnricherTest {

    private fun obj(text: String): PointObject {
        val f = File.createTempFile("ent", ".txt").apply { writeText(text); deleteOnExit() }
        return PointObject("id", "text/plain", ScratchRef(f.absolutePath), ObjectState(ObjectKind.TEXT))
    }

    private fun extractor(vararg entities: Entity) = object : EntityExtractor {
        override suspend fun extract(text: String) = entities.toList()
    }

    @Test
    fun `maps phone and email to features, ignores unhandled types`() = runTest {
        val enricher = EntityEnricher(
            extractor(
                Entity(EntityType.PHONE, "+380671234567"),
                Entity(EntityType.EMAIL, "a@b.com"),
                Entity(EntityType.MONEY, "$5"), // not yet actionable → ignored
            ),
        )
        val features = enricher.enrich(obj("call +380671234567 a@b.com"))
        assertTrue(Feature.HAS_PHONE in features)
        assertTrue(Feature.HAS_EMAIL in features)
        assertEquals(2, features.size)
    }

    @Test
    fun `applies only to text objects`() {
        val enricher = EntityEnricher(extractor())
        assertTrue(enricher.appliesTo(ObjectState(ObjectKind.TEXT)))
        assertFalse(enricher.appliesTo(ObjectState(ObjectKind.IMAGE)))
    }

    @Test
    fun `blank text yields no features and never calls the extractor`() = runTest {
        val enricher = EntityEnricher(extractor(Entity(EntityType.PHONE, "x")))
        assertTrue(enricher.enrich(obj("   ")).isEmpty())
    }
}
