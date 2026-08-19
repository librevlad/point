package com.point.desktop

import com.point.core.flow.Entity
import com.point.core.flow.EntityExtractor
import com.point.core.flow.EntityType
import com.point.core.flow.META_ENTITY_PREFIX
import com.point.core.flow.META_SOURCE_SUFFIX
import com.point.core.flow.alternativesOf
import com.point.core.model.ActionResult
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.Provenance
import com.point.core.model.ScratchRef
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Компьютер судит знание теми же правилами, что и телефон (#1139, #1144).
 *
 * У ПК была своя упрощённая копия: победителем становилось первое значение по тексту, спор
 * двух прочтений одного факта не замечался, а происхождение жёстко звалось «прочитано» —
 * даже у текста, который человек набрал руками.
 */
class PcKnowsLikeThePhoneTest {

    @get:Rule val temp = TemporaryFolder()

    private val phone = META_ENTITY_PREFIX + "phone"

    private fun typedText(content: String): PointObject {
        val file = temp.newFile("note.txt").apply { writeText(content) }
        return PointObject("obj", "text/plain", ScratchRef(file.absolutePath), ObjectState(ObjectKind.TEXT))
    }

    private fun extractor(vararg found: Entity) = object : EntityExtractor {
        override suspend fun extract(text: String): List<Entity> = found.toList()
    }

    @Test fun `набранный руками текст не выдаётся за прочитанный с кадра`() = runBlocking {
        val obj = typedText("Тел: 918-682-1561")
        val result = PcEntitiesRealizer(extractor(Entity(EntityType.PHONE, "918-682-1561")))
            .perform(obj, null) as ActionResult.Done

        val src = result.findings!!.metadata[phone + META_SOURCE_SUFFIX]
        assertTrue("происхождение не названо", src != null)
        assertTrue("набранный текст назван чтением с кадра", src != Provenance.OCR.wire)
    }

    @Test fun `спор двух прочтений одного номера виден, побеждает более полное`() = runBlocking {
        val obj = typedText("Тел: 918-682-1561, (918) 682-1561 доб. 4")
        val result = PcEntitiesRealizer(
            extractor(
                Entity(EntityType.PHONE, "918-682-1561"),
                Entity(EntityType.PHONE, "(918) 682-1561 доб. 4"),
            ),
        ).perform(obj, null) as ActionResult.Done

        val meta = result.findings!!.metadata
        assertTrue(
            "спор прочтений одного факта потерян",
            alternativesOf(meta, phone).isNotEmpty() || meta[phone] != null,
        )
    }

    @Test fun `узлы находок рождаются и на компьютере — вход в найденное один и тот же`() = runBlocking {
        val obj = typedText("Тел: 918-682-1561")
        val result = PcEntitiesRealizer(extractor(Entity(EntityType.PHONE, "918-682-1561")))
            .perform(obj, null) as ActionResult.Done

        assertEquals("узел телефона не родился", 1, result.findings!!.objects.size)
    }
}
