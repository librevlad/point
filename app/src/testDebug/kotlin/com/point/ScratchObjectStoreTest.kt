package com.point

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.point.core.flow.META_SIZE
import com.point.core.flow.ObjectClassifier
import com.point.core.model.ObjectKind
import com.point.data.ScratchObjectStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Приёмник объекта — на настоящем хранилище, а не на подделке (#239).
 *
 * Через `ingest` проходит КАЖДЫЙ объект, попавший в Point, и до сих пор у этой реализации не было
 * ни одного теста: проверялись контракты вокруг неё, а сама копия байтов — нет. Здесь смотрят на
 * диск: файл появился, содержимое то же, оригинал больше не нужен, `clear()` уносит байты.
 *
 * Тест живёт в `:app`, а не в `:data`, потому что настоящее хранилище требует настоящего Android
 * (`Context`, `Uri`, `ContentResolver`) — а станок, поднимающий Android в JVM, уже стоит здесь
 * (`ScreenHarnessTest`, Robolectric). Заводить второй такой же в соседнем модуле ради трёх
 * проверок дороже, чем назвать это вслух.
 */
@RunWith(RobolectricTestRunner::class)
class ScratchObjectStoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val store = ScratchObjectStore(context, ObjectClassifier())
    private val scratch = File(context.filesDir, "scratch")

    private fun source(name: String, text: String): File =
        File(context.cacheDir, name).apply { parentFile?.mkdirs(); writeText(text) }

    /** Ссылка на файл ровно в том виде, в каком её строит дверь «Поделиться» для расшаренного
     *  текста (`File.toURI()`), — с честным разделителем пути на любой машине. */
    private fun link(file: File): String = file.toURI().toString()

    private fun files(dir: File): List<File> = dir.walkTopDown().filter(File::isFile).toList()

    @Test fun `приём кладёт копию объекта на диск`() = runBlocking {
        val original = source("nakladnaya.txt", "№ 4512 · до пятницы")

        val obj = store.ingest(link(original), "text/plain")

        val copy = File(obj.uri.value)
        assertTrue("копии объекта нет на диске", copy.isFile)
        assertEquals("№ 4512 · до пятницы", copy.readText())
        assertEquals(ObjectKind.TEXT, obj.state.kind)
        assertEquals("nakladnaya.txt", obj.metadata["name"])
        assertEquals(original.length().toString(), obj.metadata[META_SIZE])
    }

    /**
     * Копия — не ссылка на оригинал.
     *
     * Из системного Share объект приезжает `content://`-ссылкой, чей доступ умирает вместе с
     * принявшей его активити. Поэтому байты копируются немедленно, и дальше вся работа идёт по
     * своему файлу: исчезновение источника обязано ничего не менять.
     */
    @Test fun `копия живёт своей жизнью — оригинал больше не нужен`() = runBlocking {
        val original = source("recept.txt", "Мука, вода, соль")

        val obj = store.ingest(link(original), "text/plain")
        val copy = File(obj.uri.value)
        assertNotEquals("работаем с оригиналом, а не с копией", original.absolutePath, copy.absolutePath)
        assertTrue(original.delete())

        assertEquals("Мука, вода, соль", copy.readText())
    }

    /**
     * Уборка судится по диску, а не по вызову.
     *
     * «По окончании флоу рабочая копия стирается» — обещание про байты, и проверять его счётчиком
     * вызовов значит проверять другое утверждение.
     */
    @Test fun `clear уносит байты объекта с диска`() = runBlocking {
        val obj = store.ingest(link(source("parol.txt", "Пароль от почты — 4512")), "text/plain")
        val copy = File(obj.uri.value)
        assertTrue("копии объекта нет на диске — стирать нечего", copy.isFile)

        store.clear()

        assertFalse("рабочая копия пережила уборку", copy.exists())
        assertTrue("в рабочей папке остались файлы: ${files(scratch).map(File::getName)}", files(scratch).isEmpty())
    }
}
