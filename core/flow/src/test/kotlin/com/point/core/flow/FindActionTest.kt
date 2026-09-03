package com.point.core.flow

import com.point.core.model.ActionResult
import com.point.core.model.Feature
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FindActionTest {

    private val capability = FindCapability()

    private fun dump(vararg atoms: Atom): String {
        val file = File.createTempFile("atoms", ".tsv").apply { deleteOnExit() }
        file.writeText(AtomCodec.encode(AtomLayer(atoms.toList())))
        return file.absolutePath
    }

    private fun page(metadata: Map<String, String>) =
        PointObject("id", "image/jpeg", ScratchRef("/page.jpg"), ObjectState(ObjectKind.IMAGE), metadata)

    private val waybill = arrayOf(
        Atom("a1", "Одержувач", Box(10f, 100f, 120f, 120f)),
        Atom("a2", "Іванов", Box(125f, 100f, 195f, 120f)),
        Atom("a3", "Спожито", Box(10f, 200f, 90f, 220f)),
        Atom("a4", "20 842", Box(95f, 200f, 170f, 220f)),
    )

    @Test
    fun `действие есть только у страницы со слоем слов`() {
        assertTrue(capability.accepts(ObjectState(ObjectKind.IMAGE, setOf(Feature.HAS_WORD_LAYER))))

        assertFalse(capability.accepts(ObjectState(ObjectKind.TEXT, setOf(Feature.HAS_TEXT))))
        assertFalse(capability.accepts(ObjectState(ObjectKind.IMAGE)))
    }

    @Test
    fun `на PDF действие называет недостающий шаг, а не молчит`() {
        assertEquals("разложите на страницы", capability.missing(ObjectState(ObjectKind.PDF)))
        assertNull(capability.missing(ObjectState(ObjectKind.IMAGE)))
    }

    @Test
    fun `действие ничего не создаёт — находки показываются на этой же странице`() {
        val state = ObjectState(ObjectKind.IMAGE, setOf(Feature.HAS_WORD_LAYER))
        assertEquals(state, capability.produces(state))
        assertEquals(setOf(com.point.core.model.Intent.UNDERSTAND), capability.intents(state))
    }

    @Test
    fun `без запроса действие спрашивает, что искать`() = runTest {
        val obj = page(mapOf(META_OCR_ATOMS_REF to dump(*waybill)))
        val result = FindRealizer().perform(obj, null)

        assertTrue(result is ActionResult.NeedsInput)
        assertEquals("Что найти в документе?", (result as ActionResult.NeedsInput).prompt)

        assertTrue(FindRealizer().perform(obj, "  —  ") is ActionResult.NeedsInput)
    }

    @Test
    fun `найденное считается местами на странице`() = runTest {
        val obj = page(mapOf(META_OCR_ATOMS_REF to dump(*waybill)))

        assertEquals("Нашлось 1 место", (FindRealizer().perform(obj, "іванов") as ActionResult.Done).message)

        assertEquals("Нашлось 1 место", (FindRealizer().perform(obj, "20842") as ActionResult.Done).message)
    }

    @Test
    fun `ненайденное говорится прямо и не красится в отказ`() = runTest {
        val result = FindRealizer().perform(page(mapOf(META_OCR_ATOMS_REF to dump(*waybill))), "Петров")

        assertTrue(result is ActionResult.Done)
        assertEquals("Ничего не нашлось", (result as ActionResult.Done).message)
    }

    @Test
    fun `без слоя слов действие отказывает, а не отвечает пустотой`() = runTest {
        val result = FindRealizer().perform(page(mapOf(META_OCR_ATOMS_REF to "/нет/такого.tsv")), "Іванов")

        assertTrue(result is ActionResult.Failure)
        assertEquals("Страница ещё не прочитана — искать не в чем", (result as ActionResult.Failure).reason)
    }
}
