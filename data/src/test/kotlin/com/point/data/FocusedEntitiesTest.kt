package com.point.data

import com.point.core.flow.Atom
import com.point.core.flow.AtomCodec
import com.point.core.flow.AtomLayer
import com.point.core.flow.Box
import com.point.core.flow.Entity
import com.point.core.flow.EntityExtractor
import com.point.core.flow.EntityType
import com.point.core.flow.Focus
import com.point.core.flow.META_AT_REGION
import com.point.core.flow.META_MORE_SUFFIX
import com.point.core.flow.META_OCR_ATOMS_REF
import com.point.core.flow.moreOf
import com.point.core.flow.regionOfWire
import com.point.core.flow.withFocus
import com.point.core.model.ActionResult
import com.point.core.model.Findings
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Обязательный сценарий Этапа 3: два телефона на одном фото, Focus по очереди на каждом.
 * Два разных semantic object, разные id и `at.region`, первый не перезаписан вторым.
 */
class FocusedEntitiesTest {

    private val phone1 = "+380671111111"
    private val phone2 = "+380672222222"

    private val layer = AtomLayer(
        listOf(
            Atom("w1", "тел:", Box(10f, 20f, 60f, 40f), confidence = 0.99f),
            Atom("w2", phone1, Box(70f, 20f, 260f, 40f), confidence = 0.99f),
            Atom("w3", "запасной:", Box(10f, 220f, 100f, 240f), confidence = 0.99f),
            Atom("w4", phone2, Box(110f, 220f, 300f, 240f), confidence = 0.99f),
        ),
    )

    private val areaA = Focus("img", region = Box(0f, 0f, 320f, 100f))

    private val areaB = Focus("img", region = Box(0f, 200f, 320f, 300f))

    private val phones = object : EntityExtractor {
        override suspend fun extract(text: String): List<Entity> =
            Regex("""\+380\d{9}""").findAll(text).map { Entity(EntityType.PHONE, it.value) }.toList()
    }

    private fun photo(extraMetadata: Map<String, String> = emptyMap()): PointObject {
        val atoms = File.createTempFile("atoms", ".tsv").apply {
            writeText(AtomCodec.encode(layer))
            deleteOnExit()
        }
        return PointObject(
            id = "img",
            mime = "image/jpeg",
            uri = ScratchRef("/tmp/img.jpg"),
            state = ObjectState(ObjectKind.IMAGE),
            metadata = mapOf(META_OCR_ATOMS_REF to atoms.absolutePath) + extraMetadata,
        )
    }

    private suspend fun look(obj: PointObject, focus: Focus): Findings {
        val result = EntityInvestigationRealizer(phones)
            .perform(obj.copy(metadata = withFocus(obj.metadata, focus)), null)
        check(result is ActionResult.Done) { "ожидалось знание, получено $result" }
        return result.findings ?: Findings()
    }

    @Test
    fun `два телефона в двух областях — два объекта, каждый на своём месте`() = runTest {
        val first = look(photo(), areaA)
        val phoneA = first.objects.single()
        assertEquals("img:phone:380671111111", phoneA.id)
        assertEquals(phone1, first.metadata["entity.phone"])

        // знание области A уже в объекте — Focus B видит его
        val known = photo(extraMetadata = first.metadata.filterKeys { it == "entity.phone" })
        val second = look(known, areaB)
        val phoneB = second.objects.single()

        assertEquals("img:phone:380672222222", phoneB.id)
        assertTrue("id различны", phoneA.id != phoneB.id)
        assertTrue(
            "места различны",
            phoneA.metadata[META_AT_REGION] != phoneB.metadata[META_AT_REGION],
        )

        assertEquals("первый телефон не перезаписан", null, second.metadata["entity.phone"])
        assertEquals("второй — ещё одно значение того же вида", listOf(phone2), moreOf(second.metadata, "entity.phone"))
    }

    @Test
    fun `тот же телефон из того же Focus не плодит дублей`() = runTest {
        val known = photo(extraMetadata = mapOf("entity.phone" to phone1))
        val again = look(known, areaA)

        assertTrue("то же значение — не новый объект", again.objects.isEmpty())
        assertTrue(again.metadata.isEmpty())
    }

    @Test
    fun `atom ids сильнее геометрии`() = runTest {

        val idsOfSecond = Focus("img", region = areaA.region, atomIds = listOf("w3", "w4"))
        val found = look(photo(), idsOfSecond)

        assertEquals("указанные атомы победили регион", "img:phone:380672222222", found.objects.single().id)
    }

    @Test
    fun `регион результата лежит в своей области`() = runTest {
        val found = look(photo(), areaB)
        val at = regionOfWire(found.objects.single().metadata[META_AT_REGION])!!

        assertTrue("регион области B ниже области A", at.top >= 100f)
    }

    @Test
    fun `пустая область — честное ничего, а не выдумка`() = runTest {
        val empty = look(photo(), Focus("img", region = Box(500f, 500f, 600f, 600f)))

        assertTrue(empty.objects.isEmpty())
        assertTrue(empty.metadata.isEmpty())
    }
}
