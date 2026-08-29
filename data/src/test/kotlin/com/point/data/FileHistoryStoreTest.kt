package com.point.data

import com.point.core.flow.META_GRAPH_ROLE_PREFIX
import com.point.core.flow.META_OCR_ATOMS_REF
import com.point.core.flow.META_OCR_TEXT_REF
import com.point.core.flow.META_SEMANTIC_SUMMARY
import com.point.core.flow.META_SIZE
import com.point.core.flow.META_STRENGTH_SUFFIX
import com.point.core.flow.ObjectClassifier
import com.point.core.flow.InvestigationState
import com.point.core.flow.READING_STRONG
import com.point.core.flow.REFRESHABLE_KNOWLEDGE
import com.point.core.flow.mergeKnowledge
import com.point.core.flow.withInvestigation
import com.point.core.model.CapabilityId
import com.point.core.model.Feature
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
        assertEquals("+380671234567", entry.metadata["entity.phone"])
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
    fun `улика без настоящего scratch-файла не переживает запись — путь не воскресает`() = runTest {
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

        assertTrue("сущность потеряна вместе с ним", Feature.HAS_PHONE in reopened.state.features)
        assertNull("несуществующий путь к улике приехал в новый прогон", reopened.metadata[META_OCR_TEXT_REF])
        assertNull("несуществующий путь к слою слов приехал в новый прогон", reopened.metadata[META_OCR_ATOMS_REF])
    }

    /**
     * #1242: улика, потерявшая свой файл, не воскресает — а пометка силы при ней воскресала.
     * Объект возвращался из «Недавнего» с «здесь прочитано сильнее» при пустом месте, и первое
     * же его чтение уходило в «или»: текста у объекта не оставалось вовсе, и снять пометку было
     * нечем.
     */
    @Test
    fun `пометка силы не переживает прочтение, к которому относится (#1242)`() = runTest {
        val strengthKey = META_OCR_TEXT_REF + META_STRENGTH_SUFFIX
        store.record(textObject("a", "текст", "скан.txt"))
        store.update(
            textObject("a", "текст", "скан.txt").copy(
                state = ObjectState(ObjectKind.TEXT, setOf(Feature.HAS_TEXT)),
                metadata = mapOf(
                    "name" to "скан.txt",
                    META_OCR_TEXT_REF to "/scratch/сильное.txt",
                    strengthKey to READING_STRONG,
                ),
            ),
        )

        val reopened = store.open("a")!!

        assertNull("прочтение воскресло висящим путём", reopened.metadata[META_OCR_TEXT_REF])
        assertNull("пометка силы пережила своё прочтение", reopened.metadata[strengthKey])

        // Первое чтение объекта после возврата обязано стать его текстом, а не расхождением.
        val read = "/scratch/новое.txt"
        val merged = mergeKnowledge(
            reopened.metadata,
            mapOf(META_OCR_TEXT_REF to read),
            refreshable = REFRESHABLE_KNOWLEDGE,
        )
        assertEquals("после «Недавнего» у объекта нет прочтения вовсе", read, merged[META_OCR_TEXT_REF])
    }

    @Test
    fun `улика — не просто путь, а содержимое — копия переживает запись даже после очистки scratch (#687)`() = runTest {
        store.record(textObject("a", "страница счёта", "скан.txt"))
        val scratchText = File.createTempFile("ocr-", ".txt").apply { writeText("распознанный текст страницы") }
        val scratchAtoms = File.createTempFile("ocr-", ".tsv").apply { writeText("word\t0\t0\t10\t10") }
        store.update(
            textObject("a", "страница счёта", "скан.txt").copy(
                state = ObjectState(ObjectKind.TEXT, setOf(Feature.HAS_TEXT, Feature.HAS_WORD_LAYER)),
                metadata = mapOf(
                    "name" to "скан.txt",
                    META_OCR_TEXT_REF to scratchText.absolutePath,
                    META_OCR_ATOMS_REF to scratchAtoms.absolutePath,
                ),
            ),
        )

        // Point чистит scratch после каждого flow (ObjectStore.clear()) — здесь это смоделировано явно.
        scratchText.delete()
        scratchAtoms.delete()

        val reopened = store.open("a")!!

        assertTrue("признак прочитанного текста потерян", Feature.HAS_TEXT in reopened.state.features)
        assertTrue("признак слоя слов потерян", Feature.HAS_WORD_LAYER in reopened.state.features)
        val textRef = reopened.metadata[META_OCR_TEXT_REF]
        assertTrue("улика не пережила очистку scratch", textRef != null && File(textRef).isFile)
        assertNotEquals("это обязана быть копия, а не тот же протухший путь", scratchText.absolutePath, textRef)
        assertEquals("распознанный текст страницы", File(textRef!!).readText())
        val atomsRef = reopened.metadata[META_OCR_ATOMS_REF]
        assertTrue("улика со словами не пережила очистку scratch", atomsRef != null && File(atomsRef).isFile)
    }

    @Test
    fun `тот же id возвращается при повторном входе — это тот же объект, а не новый (#687)`() = runTest {
        store.record(textObject("a", "hello", "a.txt"))

        assertEquals("a", store.open("a")!!.id)
    }

    @Test
    fun `суть, роли и статус исследования переживают повторный вход — не только сущности (#687)`() = runTest {
        store.record(textObject("a", "перевод от Иванова", "чек.txt"))
        store.update(
            textObject("a", "перевод от Иванова", "чек.txt").copy(
                metadata = mapOf(
                    "name" to "чек.txt",
                    META_SEMANTIC_SUMMARY to "Перевод от Иванова на 1000 грн",
                    (META_GRAPH_ROLE_PREFIX + "sender") to "Иванов",
                ) + withInvestigation(emptyMap(), CapabilityId("ocr-investigation"), InvestigationState.FOUND),
            ),
        )

        val reopened = store.open("a")!!

        assertEquals("Перевод от Иванова на 1000 грн", reopened.metadata[META_SEMANTIC_SUMMARY])
        assertEquals("Иванов", reopened.metadata[META_GRAPH_ROLE_PREFIX + "sender"])
        assertEquals("found", reopened.metadata["investigated.ocr-investigation"])
    }

    @Test
    fun `журнал версии до #687 отдаёт сущности через metadata`() = runTest {
        val copy = File(dir, "old.txt").apply { writeText("x") }
        val legacy = org.json.JSONObject()
            .put("id", "old").put("mime", "text/plain").put("kind", "TEXT")
            .put("name", "old.txt").put("t", 123L).put("path", copy.absolutePath)
            .put("entities", org.json.JSONObject().put("phone", "+380671234567"))
        File(dir, "index.jsonl").appendText(legacy.toString() + "\n")

        val reopened = store.open("old")!!

        assertEquals("old", reopened.id)
        assertEquals("+380671234567", reopened.metadata["entity.phone"])
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
        assertTrue(entry.metadata.isEmpty())
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

    /** Улика, положенная рядом с объектом: то же, что делает настоящее обогащение. */
    private suspend fun withEvidence(id: String, name: String, text: String) {
        store.record(textObject(id, "страница", name))
        val scratch = File.createTempFile("ocr-", ".txt").apply { writeText(text); deleteOnExit() }
        store.update(
            textObject(id, "страница", name).copy(
                state = ObjectState(ObjectKind.TEXT, setOf(Feature.HAS_TEXT)),
                metadata = mapOf("name" to name, META_OCR_TEXT_REF to scratch.absolutePath),
            ),
        )
        scratch.delete()
    }

    private fun filesOnDisk(): List<String> =
        dir.listFiles().orEmpty().map { it.name }.filter { it != "index.jsonl" }.sorted()

    @Test
    fun `убранная запись уходит из «Недавнего», соседние остаются (#543)`() = runTest {
        store.record(textObject("a", "первый", "a.txt"))
        store.record(textObject("b", "второй", "b.txt"))

        store.remove("a")

        assertEquals(listOf("b"), store.recent().map { it.id })
        assertNull("убранная запись всё ещё открывается", store.open("a"))
        assertEquals("второй", File(store.open("b")!!.uri.value).readText())
    }

    @Test
    fun `файл убранной записи исчезает с диска, чужой — нет (#543)`() = runTest {
        store.record(textObject("a", "первый", "a.txt"))
        store.record(textObject("b", "второй", "b.txt"))
        val gone = File(store.recent().first { it.id == "a" }.ref.value)
        val kept = File(store.recent().first { it.id == "b" }.ref.value)

        store.remove("a")

        assertTrue("файл убранной записи остался на диске: ${gone.name}", !gone.exists())
        assertTrue("под руку попал соседний файл", kept.exists())
    }

    @Test
    fun `убрать запись — значит убрать и распознанный текст рядом с ней (#543)`() = runTest {
        withEvidence("a", "скан.txt", "распознанный текст ведомости")
        withEvidence("b", "чек.txt", "чужой текст")
        val evidence = File(store.open("a")!!.metadata[META_OCR_TEXT_REF]!!)
        assertTrue("улика не легла рядом с объектом", evidence.isFile)

        store.remove("a")

        assertTrue("распознанный текст остался на диске: ${evidence.name}", !evidence.exists())
        assertTrue(
            "рядом с убранной записью что-то осталось: ${filesOnDisk()}",
            filesOnDisk().none { it.startsWith("a") },
        )
        assertEquals("чужой текст", File(store.open("b")!!.metadata[META_OCR_TEXT_REF]!!).readText())
    }

    @Test
    fun `вытесненная по лимиту запись не оставляет улик — иначе текст копится вечно (#543)`() = runTest {
        withEvidence("id0", "0.txt", "текст самой старой ведомости")
        val evidence = File(store.open("id0")!!.metadata[META_OCR_TEXT_REF]!!)
        assertTrue(evidence.isFile)

        repeat(55) { i -> store.record(textObject("later$i", "c$i", "$i.txt")) }

        assertNull("вытеснение не убрало саму запись", store.open("id0"))
        assertTrue("улика вытесненной записи копится на диске: ${evidence.name}", !evidence.exists())
        assertEquals(50, filesOnDisk().size)
    }

    // ---- #999: ссылка, принятая по адресу в файле, переоткрывается ссылкой — байты читаются и тут. ----

    @Test
    fun `ссылка файлом возвращается из Недавнего ссылкой со своим адресом`() = runTest {
        val address = "https://example.com/pointtest?a=1"
        val file = File.createTempFile("src-", ".uri").apply { writeText("$address\n"); deleteOnExit() }
        store.record(
            PointObject(
                id = "link",
                mime = "text/uri-list",
                uri = ScratchRef(file.absolutePath),
                state = ObjectState(ObjectKind.URL, setOf(Feature.HAS_URL)),
                metadata = mapOf("name" to "link.txt", "entity.url" to address),
            ),
        )

        val reopened = store.open("link")!!

        assertEquals(ObjectKind.URL, reopened.state.kind)
        assertEquals(address, reopened.metadata["entity.url"])
    }

    @Test
    fun `запись без адреса возвращается ссылкой со своим адресом — он читается из файла`() = runTest {
        val address = "https://example.com/staraya-zapis?a=1"
        val file = File.createTempFile("src-", ".uri")
            .apply { writeText("# сохранено из браузера\r\n\r\n$address\r\n"); deleteOnExit() }
        store.record(
            PointObject(
                id = "old-link",
                mime = "text/uri-list",
                uri = ScratchRef(file.absolutePath),
                state = ObjectState(ObjectKind.URL),
                metadata = mapOf("name" to "link.txt"),
            ),
        )

        val reopened = store.open("old-link")!!

        assertEquals(address, reopened.metadata["entity.url"])
        assertTrue("адрес есть — признак ссылки обязан стоять", reopened.state.has(Feature.HAS_URL))
    }

    @Test
    fun `файл под видом ссылки без адреса и из Недавнего возвращается файлом`() = runTest {
        val file = File.createTempFile("src-", ".uri").apply { writeText("адреса тут нет"); deleteOnExit() }
        store.record(
            PointObject(
                id = "not-a-link",
                mime = "text/uri-list",
                uri = ScratchRef(file.absolutePath),
                state = ObjectState(ObjectKind.TEXT),
                metadata = mapOf("name" to "not-a-link.txt"),
            ),
        )

        assertEquals(ObjectKind.TEXT, store.open("not-a-link")!!.state.kind)
    }

    @Test
    fun `убрать несуществующую запись — не потерять существующие (#543)`() = runTest {
        store.record(textObject("a", "первый", "a.txt"))

        store.remove("missing")

        assertEquals(listOf("a"), store.recent().map { it.id })
    }

    // ---- #1246: возврат к объекту не стирает то, что о нём уже понято. ----

    @Test
    fun `второй визит в объект из «Недавнего» не стирает распознанный текст (#1246)`() = runTest {
        val read = "распознанный текст ведомости"
        withEvidence("a", "скан.txt", read)

        // Человек открыл объект из «Недавнего»: путь улики ведёт теперь в саму историю.
        val fromHistory = store.open("a")!!
        assertEquals(
            "улика не переехала в историю — сцена не та",
            dir.absolutePath,
            File(fromHistory.metadata.getValue(META_OCR_TEXT_REF)).parentFile?.absolutePath,
        )

        // Конец фонового обогащения дописывает запись тем же путём — как при каждом визите.
        store.update(fromHistory)

        val ref = store.open("a")?.metadata?.get(META_OCR_TEXT_REF)
        assertTrue("распознанное пропало со второго визита", ref != null && File(ref).isFile)
        assertEquals("распознанное подменилось на второй заход", read, File(ref!!).readText())
    }

    @Test
    fun `перечень уплотняется и по числу строк — один визит дописывает их пачкой (#1246)`() = runTest {
        store.record(textObject("a", "страница", "скан.txt"))

        repeat(200) { store.update(textObject("a", "страница", "скан.txt")) }

        val rows = File(dir, "index.jsonl").readLines().count { it.isNotBlank() }
        assertTrue("перечень растёт без границы: $rows строк на одну запись", rows <= 150)
        assertEquals(listOf("a"), store.recent().map { it.id })
    }

    @Test
    fun `убранная запись не возвращается после перезапуска — журнал переписан (#543)`() = runTest {
        store.record(textObject("a", "первый", "a.txt"))
        store.record(textObject("b", "второй", "b.txt"))

        store.remove("a")

        val afterRestart = FileHistoryStore(dir, ObjectClassifier())
        assertEquals(listOf("b"), afterRestart.recent().map { it.id })
    }
}
