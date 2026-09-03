package com.point.desktop

import com.point.core.flow.OoxmlOfficeTextExtractor
import com.point.core.flow.SlidesRealizer
import com.point.core.model.ActionResult
import com.point.core.model.Feature
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Хранилище компьютера держит тот же контракт, что и телефон (#1412): место под новый файл —
 * только путь, файла по нему ещё нет.
 *
 * Общий код опирался на это молча: «Слайды» превращают полученное место в папку набора
 * (`mkdirs()`), а `File.createTempFile` уже положил туда пустой файл — `mkdirs()` возвращал
 * `false`, запись первого слайда падала, и человек на компьютере читал «Не удалось разобрать
 * презентацию» про целый документ. Гейт #1407 этого не видел: тесты слайдов шли на телефонном
 * хранилище. Здесь общий исполнитель проверяется на хранилище компьютера.
 */
class PcScratchStoreTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `место под новый файл — только путь, файла по нему ещё нет`() = runTest {
        val store = PcScratchStore(tmp.newFolder("scratch"))

        val place = File(store.newScratchFile("slides").value)

        assertFalse("хранилище создало файл заранее — набор из этого места не сделать", place.exists())
        assertTrue("место лежит не в папке хранилища", place.parentFile.isDirectory)
        assertTrue("расширение потеряно", place.name.endsWith(".slides"))
    }

    @Test
    fun `два места подряд не совпадают`() = runTest {
        val store = PcScratchStore(tmp.newFolder("scratch"))

        val first = store.newScratchFile("txt").value
        val second = store.newScratchFile("txt").value

        assertTrue("два результата легли бы в один файл", first != second)
    }

    /** Живая охота 03.09.2026: «Слайды» на компьютере — общий исполнитель на хранилище компьютера. */
    @Test
    fun `слайды на компьютере рождают набор, а не отказ`() = runTest {
        val store = PcScratchStore(tmp.newFolder("scratch"))
        val realizer = SlidesRealizer(store, OoxmlOfficeTextExtractor())

        val result = realizer.perform(presentation(), null)

        assertTrue("слайды не разобрались: $result", result is ActionResult.Success)
        val born = (result as ActionResult.Success).result
        assertEquals(ObjectKind.COLLECTION, born.type)
        val dir = File(born.uri.value)
        assertTrue("набор — не папка", dir.isDirectory)
        val first = File(dir, SlidesRealizer.slideName(1))
        assertTrue("первого слайда в наборе нет: ${dir.list()?.toList()}", first.isFile)
        assertTrue("слова слайда не записаны", first.readText().contains("Point"))
        assertEquals("1", born.metadata["count"])
    }

    private fun presentation(): PointObject {
        val bundled = javaClass.getResourceAsStream("/$FIXTURE")
            ?: throw AssertionError("фикстуры $FIXTURE_IN_TREE нет на classpath — разбирать нечего")
        val file = File(tmp.newFolder(), FIXTURE).apply { outputStream().use(bundled::copyTo) }
        return PointObject(
            id = "pptx-1",
            uri = ScratchRef(file.absolutePath),
            mime = PPTX,
            state = ObjectState(ObjectKind.OFFICE, setOf(Feature.IS_PRESENTATION)),
            metadata = mapOf("name" to FIXTURE),
        )
    }

    private companion object {

        const val FIXTURE = "office-to-pdf.pptx"

        const val FIXTURE_IN_TREE = "desktop/src/test/resources/$FIXTURE"

        const val PPTX = "application/vnd.openxmlformats-officedocument.presentationml.presentation"
    }
}
