package com.point

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.point.core.flow.META_ENTITY_PREFIX
import com.point.core.flow.META_SIZE
import com.point.core.flow.META_UNUSABLE_REASON
import com.point.core.flow.ObjectClassifier
import com.point.core.model.Feature
import com.point.core.model.ObjectKind
import com.point.data.ScratchObjectStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class ScratchObjectStoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val scratch = File(context.filesDir, "scratch")
    private val store = ScratchObjectStore(context, ObjectClassifier(), scratch)

    private fun source(name: String, text: String): File =
        File(context.cacheDir, name).apply { parentFile?.mkdirs(); writeText(text) }

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

    @Test fun `копия живёт своей жизнью — оригинал больше не нужен`() = runBlocking {
        val original = source("recept.txt", "Мука, вода, соль")

        val obj = store.ingest(link(original), "text/plain")
        val copy = File(obj.uri.value)
        assertNotEquals("работаем с оригиналом, а не с копией", original.absolutePath, copy.absolutePath)
        assertTrue(original.delete())

        assertEquals("Мука, вода, соль", copy.readText())
    }

    @Test fun `пустой файл называет свою причину сразу, на первом экране`() = runBlocking {
        val empty = source("prazdno.txt", "")

        val obj = store.ingest(link(empty), "text/plain")

        assertTrue("пустой файл обязан быть отмечен негодным", obj.state.has(Feature.UNUSABLE))
        assertEquals("Файл пустой — в нём нечего читать", obj.metadata[META_UNUSABLE_REASON])
    }

    @Test fun `файл с содержимым не несёт пометки негодности`() = runBlocking {
        val filled = source("nakladnaya.txt", "№ 4512 · до пятницы")

        val obj = store.ingest(link(filled), "text/plain")

        assertFalse(obj.state.has(Feature.UNUSABLE))
        assertNull(obj.metadata[META_UNUSABLE_REASON])
    }

    // ---- #999: ссылка, переданная файлом, знает свой адрес; файл без адреса — не ссылка. ----

    @Test fun `ссылка файлом приходит со своим адресом — как ссылка текстом`() = runBlocking {
        val address = "https://example.com/pointtest?a=1"
        val shared = source("link.txt", "$address\n")

        val obj = store.ingest(link(shared), "text/uri-list")

        assertEquals(ObjectKind.URL, obj.state.kind)
        assertEquals(address, obj.metadata[META_ENTITY_PREFIX + "url"])
        assertTrue("адрес есть — признак ссылки обязан стоять", obj.state.has(Feature.HAS_URL))
    }

    @Test fun `адрес читается целиком, а не по голове файла`() = runBlocking {
        val address = "https://example.com/very/long/path?q=" + "a".repeat(900)
        val shared = source("long-link.txt", "# комментарий перед адресом\r\n$address\r\n")

        val obj = store.ingest(link(shared), "text/uri-list")

        assertEquals(ObjectKind.URL, obj.state.kind)
        assertEquals(address, obj.metadata[META_ENTITY_PREFIX + "url"])
    }

    @Test fun `файл под видом ссылки без адреса — файл, а не ссылка`() = runBlocking {
        val notALink = source("not-a-link.txt", "просто строка, адреса тут нет")

        val obj = store.ingest(link(notALink), "text/uri-list")

        assertEquals(ObjectKind.TEXT, obj.state.kind)
        assertNull(obj.metadata[META_ENTITY_PREFIX + "url"])
        assertFalse(obj.state.has(Feature.HAS_URL))
    }

    @Test fun `clear уносит байты объекта с диска`() = runBlocking {
        val obj = store.ingest(link(source("parol.txt", "Пароль от почты — 4512")), "text/plain")
        val copy = File(obj.uri.value)
        assertTrue("копии объекта нет на диске — стирать нечего", copy.isFile)

        store.clear()

        assertFalse("рабочая копия пережила уборку", copy.exists())
        assertTrue("в рабочей папке остались файлы: ${files(scratch).map(File::getName)}", files(scratch).isEmpty())
    }
}
