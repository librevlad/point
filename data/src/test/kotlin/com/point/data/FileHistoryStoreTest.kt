package com.point.data

import com.point.core.flow.META_OCR_ATOMS_REF
import com.point.core.flow.META_OCR_TEXT_REF
import com.point.core.flow.META_SIZE
import com.point.core.flow.ObjectClassifier
import com.point.core.model.Feature
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class FileHistoryStoreTest {

    private val dir = Files.createTempDirectory("point-history").toFile().apply { deleteOnExit() }
    private val store = FileHistoryStore(dir, ObjectClassifier())

    private fun textObject(id: String, content: String, name: String): PointObject {
        val file = File.createTempFile("src-", ".txt").apply { writeText(content); deleteOnExit() }
        return PointObject(
            id = id,
            mime = "text/plain",
            uri = ScratchRef(file.absolutePath),
            state = ObjectState(ObjectKind.TEXT),
            metadata = mapOf("name" to name),
        )
    }

    @Test
    fun `record then recent round-trips, newest first`() = runTest {
        store.record(textObject("a", "first", "a.txt"))
        store.record(textObject("b", "second", "b.txt"))

        val recent = store.recent()
        assertEquals(listOf("b", "a"), recent.map { it.id })
        assertEquals("b.txt", recent[0].name)
        assertEquals(ObjectKind.TEXT, recent[0].kind)
    }

    @Test
    fun `recent is newest-first even when records share a timestamp`() = runTest {

        repeat(6) { i -> store.record(textObject("id$i", "c$i", "$i.txt")) }
        assertEquals(
            listOf("id5", "id4", "id3", "id2", "id1", "id0"),
            store.recent().map { it.id },
        )
    }

    @Test
    fun `open re-materialises the persisted copy`() = runTest {
        store.record(textObject("a", "hello", "a.txt"))
        val reopened = store.open("a")!!

        assertEquals("hello", File(reopened.uri.value).readText())
        assertEquals(ObjectKind.TEXT, reopened.state.kind)
    }

    @Test
    fun `переоткрытый объект знает свой вес — иначе экран нечем мерить`() = runTest {

        store.record(textObject("a", "hello", "a.txt"))

        assertEquals("5", store.open("a")!!.metadata[META_SIZE])
    }

    @Test
    fun `same id de-duplicates keeping the latest`() = runTest {
        store.record(textObject("a", "v1", "old.txt"))
        store.record(textObject("a", "v2", "new.txt"))
        val recent = store.recent()
        assertEquals(1, recent.size)
        assertEquals("new.txt", recent[0].name)
    }

    @Test
    fun `open returns null for unknown id`() = runTest {
        assertNull(store.open("missing"))
    }

    @Test
    fun `clearAll wipes history`() = runTest {
        store.record(textObject("a", "x", "a.txt"))
        store.clearAll()
        assertTrue(store.recent().isEmpty())
    }

    @Test
    fun `update appends the understanding — recent carries features and entity values`() = runTest {
        store.record(textObject("a", "звони +380671234567", "a.txt"))
        val enriched = textObject("a", "звони +380671234567", "a.txt").copy(
            state = ObjectState(ObjectKind.TEXT, setOf(Feature.HAS_PHONE)),
            metadata = mapOf("name" to "a.txt", "entity.phone" to "+380671234567"),
        )

        store.update(enriched)

        val entry = store.recent().single()
        assertTrue(Feature.HAS_PHONE in entry.features)
        assertEquals("+380671234567", entry.entities["phone"])
    }

    @Test
    fun `переоткрытый объект приносит понятое раньше, а не пустое состояние`() = runTest {

        store.record(textObject("a", "звони +380671234567", "чек.txt"))
        store.update(
            textObject("a", "звони +380671234567", "чек.txt").copy(
                state = ObjectState(ObjectKind.TEXT, setOf(Feature.HAS_PHONE, Feature.HAS_DATE)),
                metadata = mapOf(
                    "name" to "чек.txt",
                    "entity.phone" to "+380671234567",
                    "entity.date" to "завтра 18:00",
                ),
            ),
        )

        val reopened = store.open("a")!!

        assertTrue("признак телефона потерян", Feature.HAS_PHONE in reopened.state.features)
        assertTrue("признак даты потерян", Feature.HAS_DATE in reopened.state.features)
        assertEquals("+380671234567", reopened.metadata["entity.phone"])
        assertEquals("завтра 18:00", reopened.metadata["entity.date"])
    }

    @Test
    fun `признаки, опирающиеся на стёртые файлы, не воскресают`() = runTest {
        store.record(textObject("a", "текст", "скан.txt"))
        store.update(
            textObject("a", "текст", "скан.txt").copy(
                state = ObjectState(
                    ObjectKind.TEXT,
                    setOf(Feature.HAS_PHONE, Feature.HAS_TEXT, Feature.HAS_WORD_LAYER),
                ),
                metadata = mapOf(
                    "name" to "скан.txt",
                    "entity.phone" to "+380671234567",

                    META_OCR_TEXT_REF to "/scratch/старый.txt",
                    META_OCR_ATOMS_REF to "/scratch/старый.tsv",
                ),
            ),
        )

        val reopened = store.open("a")!!

        assertTrue("вернулся признак прочитанного текста без самого текста", Feature.HAS_TEXT !in reopened.state.features)
        assertTrue("вернулся слой слов без слоя слов", Feature.HAS_WORD_LAYER !in reopened.state.features)
        assertTrue("сущность потеряна вместе с ним", Feature.HAS_PHONE in reopened.state.features)
        assertNull("протухший указатель на файл приехал в новый прогон", reopened.metadata[META_OCR_TEXT_REF])
        assertNull("протухший указатель на слой слов приехал в новый прогон", reopened.metadata[META_OCR_ATOMS_REF])
    }

    @Test
    fun `имя-фраза не превращается в расширение файла`() = runTest {
        store.record(textObject("a", "содержимое", "Купить 1.5 кг сахара и хлеб"))

        val entry = store.recent().single()
        assertEquals("Купить 1.5 кг сахара и хлеб", entry.name)
        assertTrue("расширение взято из фразы: ${File(entry.ref.value).name}", File(entry.ref.value).name.endsWith(".txt"))
        assertEquals(ObjectKind.TEXT, store.open("a")!!.state.kind)
    }

    @Test
    fun `у настоящего имени файла расширение сохраняется`() = runTest {
        store.record(textObject("a", "содержимое", "отчёт.md"))

        assertTrue(File(store.recent().single().ref.value).name.endsWith(".md"))
    }

    @Test
    fun `update for an id never recorded does nothing`() = runTest {
        store.update(textObject("ghost", "x", "g.txt"))
        assertTrue(store.recent().isEmpty())
    }

    @Test
    fun `journal lines from before the understanding fields still parse`() = runTest {
        val copy = File(dir, "old.txt").apply { writeText("x") }
        val legacy = org.json.JSONObject()
            .put("id", "old").put("mime", "text/plain").put("kind", "TEXT")
            .put("name", "old.txt").put("t", 123L).put("path", copy.absolutePath)
        File(dir, "index.jsonl").appendText(legacy.toString() + "\n")

        val entry = store.recent().single()
        assertEquals("old", entry.id)
        assertTrue(entry.features.isEmpty())
        assertTrue(entry.entities.isEmpty())
    }

    @Test
    fun `history is capped — the oldest objects and their files are purged`() = runTest {

        repeat(55) { i -> store.record(textObject("id$i", "c$i", "$i.txt")) }

        assertNull(store.open("id0"))
        assertNull(store.open("id4"))
        assertEquals("c5", File(store.open("id5")!!.uri.value).readText())

        val objectFiles = dir.listFiles { f -> f.name != "index.jsonl" }?.size ?: 0
        assertEquals(50, objectFiles)
        assertEquals("id54", store.recent().first().id)
    }
}
