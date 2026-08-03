package com.point.executors

import com.point.core.flow.Atom
import com.point.core.flow.AtomCodec
import com.point.core.flow.AtomLayer
import com.point.core.flow.Box
import com.point.core.flow.META_CLOUD_ATOMS_REF
import com.point.core.flow.META_OCR_ATOMS_REF
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

/**
 * «Найти в документе» (#279): действие есть ровно там, где есть слой слов, и отвечает числом
 * найденных мест — тем же счётом, что подсвечивает экран.
 */
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

    // --- декларация: искать предлагается там, где есть в чём ---

    @Test
    fun `действие есть только у страницы со слоем слов`() {
        assertTrue(capability.accepts(ObjectState(ObjectKind.IMAGE, setOf(Feature.HAS_WORD_LAYER))))
        // Распознанный текст без страницы — не документ для поиска: подсветить находку не на чем.
        assertFalse(capability.accepts(ObjectState(ObjectKind.TEXT, setOf(Feature.HAS_TEXT))))
        assertFalse(capability.accepts(ObjectState(ObjectKind.IMAGE)))
    }

    /** Своего пути к пикселям PDF действие не заводит: страницы уже растрируются «Страницами»,
     *  и вместо тихого отсутствия человек слышит, какого одного шага не хватает (#97). */
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

    // --- поведение без экрана ---

    @Test
    fun `без запроса действие спрашивает, что искать`() = runTest {
        val obj = page(mapOf(META_OCR_ATOMS_REF to dump(*waybill)))
        val result = FindRealizer().perform(obj, null)

        assertTrue(result is ActionResult.NeedsInput)
        assertEquals("Что найти в документе?", (result as ActionResult.NeedsInput).prompt)
        // Строка из одного оформления — тоже «не спросили», а не неудачный поиск.
        assertTrue(FindRealizer().perform(obj, "  —  ") is ActionResult.NeedsInput)
    }

    @Test
    fun `найденное считается местами на странице`() = runTest {
        val obj = page(mapOf(META_OCR_ATOMS_REF to dump(*waybill)))

        assertEquals("Нашлось 1 место", (FindRealizer().perform(obj, "іванов") as ActionResult.Done).message)
        // Разрядный пробел — оформление числа: те же правила сравнения, что у свода чтений.
        assertEquals("Нашлось 1 место", (FindRealizer().perform(obj, "20842") as ActionResult.Done).message)
    }

    /** «Не нашлось» — честный ответ, а не отказ: страница прочитана, вопрос задан, ответ получен. */
    @Test
    fun `ненайденное говорится прямо и не красится в отказ`() = runTest {
        val result = FindRealizer().perform(page(mapOf(META_OCR_ATOMS_REF to dump(*waybill))), "Петров")

        assertTrue(result is ActionResult.Done)
        assertEquals("Ничего не нашлось", (result as ActionResult.Done).message)
    }

    @Test
    fun `облачный слой годится так же, как офлайновый`() = runTest {
        val obj = page(mapOf(META_CLOUD_ATOMS_REF to dump(*waybill)))

        assertEquals("Нашлось 1 место", (FindRealizer().perform(obj, "спожито") as ActionResult.Done).message)
    }

    /** Слой уехал вместе с очищенным scratch — это «искать не в чем», а не «ничего не нашлось»:
     *  две разные новости, и выдать первую за вторую значило бы соврать про документ. */
    @Test
    fun `без слоя слов действие отказывает, а не отвечает пустотой`() = runTest {
        val result = FindRealizer().perform(page(mapOf(META_OCR_ATOMS_REF to "/нет/такого.tsv")), "Іванов")

        assertTrue(result is ActionResult.Failure)
        assertEquals("Страница ещё не прочитана — искать не в чем", (result as ActionResult.Failure).reason)
    }
}
