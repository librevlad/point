package com.point.core.flow

import com.point.core.model.ActionResult
import com.point.core.model.Lang
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Перевод без ключа (#433): переводит телефон, а не модель, когда модели нет или до неё
 * нельзя. С ключом и открытым режимом всё по-прежнему — переводит модель.
 */
class TranslatesOnDeviceTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `без ключа или в закрытом режиме переводит телефон, иначе модель`() {
        assertTrue(translatesOnDevice(keySet = false, level = PrivacyLevel.FREE_FIRST))
        assertTrue(translatesOnDevice(keySet = true, level = PrivacyLevel.DEVICE_ONLY))
        assertFalse(translatesOnDevice(keySet = true, level = PrivacyLevel.FREE_FIRST))
    }

    @Test
    fun `язык, названный словами, узнаётся на трёх языках`() {
        assertEquals(Lang.EN, asked("на английский"))
        assertEquals(Lang.UK, asked("українською"))
        assertEquals(Lang.RU, asked("to Russian"))
        assertNull(asked("на японский"))
    }

    private fun text(body: String): PointObject {
        val src = File(tmp.root, "src.txt").apply { writeText(body) }
        return PointObject("id", "text/plain", ScratchRef(src.absolutePath), ObjectState(ObjectKind.TEXT))
    }

    private class Heard(private val answer: String?) : OnDeviceTranslator {
        var target: Lang? = null
        override suspend fun translate(text: String, target: Lang): String? {
            this.target = target
            return answer
        }
    }

    private fun realizer(t: OnDeviceTranslator, onDevice: Boolean = true) =
        OnDeviceTranslateRealizer(t, testKnowledge(), FileBackedStore) { onDevice }

    @Test
    fun `перевод телефона становится новым текстовым объектом, исходник цел`() = runTest {
        val heard = Heard("Hello")
        val original = "Привет, как дела? Это тест."
        val obj = text(original)

        val r = realizer(heard).perform(obj, null)

        val out = (r as ActionResult.Success).result
        assertEquals(ObjectKind.TEXT, out.type)
        assertEquals("Hello", File(out.uri.value).readText())
        assertEquals(original, File(obj.uri.value).readText())
        assertEquals(translateTargetLang(original, Lang.current()), heard.target)
    }

    @Test
    fun `названный язык уходит переводчику`() = runTest {
        val heard = Heard("Привіт")
        realizer(heard).perform(text("Hello"), "на украинский")
        assertEquals(Lang.UK, heard.target)
    }

    @Test
    fun `язык, которого телефон не знает, — честный отказ, а не молчание`() = runTest {
        val r = realizer(Heard("x")).perform(text("Hello"), "на японский")
        assertTrue(r is ActionResult.Failure && r.recoverable)
    }

    @Test
    fun `неузнанный язык текста — отказ с подсказкой про ключ`() = runTest {
        val r = realizer(Heard(null)).perform(text("Hello"), null)
        assertTrue(r is ActionResult.Failure && r.reason.contains("AI"))
    }

    @Test
    fun `срыв скачивания языка доходит до человека словами переводчика`() = runTest {
        val failing = object : OnDeviceTranslator {
            override suspend fun translate(text: String, target: Lang): String? = error("offline")
        }
        val r = realizer(failing).perform(text("Hello"), null)
        assertEquals("offline", (r as ActionResult.Failure).reason)
    }

    @Test
    fun `когда переводит модель, телефонный исполнитель недоступен`() {
        assertFalse(realizer(Heard("x"), onDevice = false).isAvailable())
        assertTrue(realizer(Heard("x"), onDevice = true).isAvailable())
    }
}
