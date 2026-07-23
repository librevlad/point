package com.point.executors

import com.point.core.flow.Entity
import com.point.core.flow.EntityExtractor
import com.point.core.flow.EntityType
import com.point.core.flow.UrlOpener
import com.point.core.model.ActionResult
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Entity realizers open the right scheme URI; ML Kit + Android launch are faked (pure JVM). */
class EntityActionsTest {

    private fun obj(text: String): PointObject {
        val f = File.createTempFile("ent", ".txt").apply { writeText(text); deleteOnExit() }
        return PointObject("id", "text/plain", ScratchRef(f.absolutePath), ObjectState(ObjectKind.TEXT))
    }

    private fun extractor(vararg entities: Entity) = object : EntityExtractor {
        override suspend fun extract(text: String) = entities.toList()
    }

    private class FakeOpener : UrlOpener {
        var opened: String? = null
        override suspend fun open(url: String) { opened = url }
    }

    @Test
    fun `call opens a tel URI with only the dialable characters`() = runTest {
        val opener = FakeOpener()
        val result = CallRealizer(extractor(Entity(EntityType.PHONE, "+380 (67) 123-45-67")), opener)
            .perform(obj("звони +380 (67) 123-45-67"))
        assertTrue(result is ActionResult.Done)
        assertEquals("tel:+380671234567", opener.opened)
    }

    @Test
    fun `sms opens an smsto URI`() = runTest {
        val opener = FakeOpener()
        SmsRealizer(extractor(Entity(EntityType.PHONE, "0671234567")), opener).perform(obj("x"))
        assertEquals("smsto:0671234567", opener.opened)
    }

    @Test
    fun `email opens a mailto URI`() = runTest {
        val opener = FakeOpener()
        EmailRealizer(extractor(Entity(EntityType.EMAIL, "ivan@x.com")), opener).perform(obj("x"))
        assertEquals("mailto:ivan@x.com", opener.opened)
    }

    @Test
    fun `no matching entity is a recoverable failure`() = runTest {
        val result = CallRealizer(extractor(), FakeOpener()).perform(obj("no phone here"))
        assertTrue(result is ActionResult.Failure)
        assertTrue((result as ActionResult.Failure).recoverable)
    }
}
