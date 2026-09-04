package com.point.data

import com.point.core.flow.HistoryFootprint
import com.point.core.flow.HistoryStore
import com.point.core.flow.extensionForFile
import com.point.core.flow.META_ENTITY_PREFIX
import com.point.core.flow.META_OCR_ATOMS_REF
import com.point.core.flow.META_OCR_TEXT_REF
import com.point.core.flow.META_SIZE
import com.point.core.flow.ObjectClassifier
import com.point.core.flow.knowingAddress
import com.point.core.flow.withoutKnowledge
import com.point.core.model.Feature
import com.point.core.model.HistoryEntry
import com.point.core.model.ObjectKind
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import com.point.data.di.HistoryDir
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FileHistoryStore @Inject constructor(
    @HistoryDir private val baseDir: File,
    private val classifier: ObjectClassifier,
) : HistoryStore {

    private val dir: File get() = baseDir.apply { mkdirs() }
    private val index: File get() = File(dir, "index.jsonl")
    private val mutex = Mutex()

    override suspend fun record(obj: PointObject) = withContext(Dispatchers.IO) {
        val source = File(obj.uri.value)
        if (!source.exists()) return@withContext
        val name = obj.metadata["name"]
        val ext = extensionForFile(name, obj.mime)
        val dest = File(dir, if (ext.isBlank()) obj.id else "${obj.id}.$ext")
        mutex.withLock {
            source.inputStream().use { input -> dest.outputStream().use { input.copyTo(it) } }
            index.appendText(
                row(
                    obj.id, obj.mime, obj.state.kind.name, name, System.currentTimeMillis(),
                    dest.absolutePath, obj.state.features, persistedMetadata(obj.id, obj.metadata),
                ) + "\n",
            )
            pruneToLimit()
        }
    }

    override suspend fun update(obj: PointObject): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            val journal = readIndex()
            val existing = journal.entries[obj.id] ?: return@withLock

            // Один визит к объекту дописывает 3–5 строк, а записей в перечне столько же
            // (#1246): уплотнение по числу **различных** объектов их не считало, и перечень
            // рос между шарингами. Уплотняем до записи — дописанная строка уже свежее всех.
            if (journal.rows >= MAX_ROWS) rewriteIndex(journal.entries.values.toList())
            index.appendText(
                row(
                    existing.id, existing.mime, existing.kind.name, existing.name, existing.epochMillis,
                    existing.ref.value, obj.state.features, persistedMetadata(obj.id, obj.metadata),
                ) + "\n",
            )
        }
    }

    /**
     * Знание объекта пишется целиком, а не только сущностями (#687): суть, роли, статус
     * исследования — те же ключи метаданных, что несёт сам объект. Улики (`ocr.text.ref` и
     * подобные) — исключение: это путь к scratch-файлу, которого после flow не станет
     * (ObjectStore.clear()). Копируем содержимое рядом с самим объектом и переписываем путь на
     * постоянный; источника нет — ключ не переживает запись: висящий путь хуже честно забытого
     * факта. Уходит он вместе с пометками при нём (#1242): пометка силы, пережившая свой текст,
     * говорит слиянию «здесь прочитано сильнее» при пустом месте, и следующее чтение объекта
     * уходит в «или».
     */
    private fun persistedMetadata(id: String, metadata: Map<String, String>): Map<String, String> =
        REF_KEYS.fold(metadata) { acc, key ->
            val value = acc[key]
            if (value.isNullOrBlank()) return@fold acc
            val copied = copyEvidence(id, key, value)
            if (copied != null) acc + (key to copied) else withoutKnowledge(acc, setOf(key))
        }

    /**
     * Улика, уже лежащая копией рядом с объектом, копируется не заново, а никуда (#1246).
     *
     * После открытия из «Недавнего» путь улики ведёт в саму эту копию, и `copyTo` получал
     * источник, равный цели: он сначала удаляет цель, а потом открывает источник — тот же
     * самый файл, — и распознанный текст исчезал с диска, а ключ выпадал из записи. Второй
     * визит к объекту стирал понятое, хотя Point обещал обратное.
     */
    private fun copyEvidence(id: String, key: String, path: String): String? {
        val source = File(path).takeIf { it.isFile } ?: return null
        val dest = evidenceFile(id, key)
        if (source.absoluteFile == dest.absoluteFile) return dest.absolutePath
        return runCatching {
            source.copyTo(dest, overwrite = true)
            dest.absolutePath
        }.getOrNull()
    }

    private fun evidenceFile(id: String, key: String) = File(dir, "$id.${key.replace('.', '-')}")

    override suspend fun recent(limit: Int): List<HistoryEntry> = withContext(Dispatchers.IO) {

        readEntries().values
            .filter { File(it.ref.value).exists() }
            .reversed()
            .take(limit)
    }

    override suspend fun open(entryId: String): PointObject? = withContext(Dispatchers.IO) {
        val entry = readEntries()[entryId] ?: return@withContext null
        val file = File(entry.ref.value)
        if (!file.exists()) return@withContext null
        val size = file.length()

        // Байты спрашиваются и при переоткрытии (#999): ссылка, принятая ссылкой по адресу в
        // файле, без них возвращалась бы из «Недавнего» текстом, а файл без адреса — ссылкой.
        // Адрес — то же самое: возврат из «Недавнего» — такое же рождение объекта из файла,
        // и записи, сделанные до #999, приходят со своим адресом, а не пустыми.
        val fresh = classifier.classify(entry.mime, size, file.name, headOf(file))
        PointObject(
            id = entry.id,
            mime = entry.mime,
            uri = ScratchRef(file.absolutePath),
            state = entry.features.fold(fresh) { state, feature -> state.with(feature) },
            metadata = restoredMetadata(entry, size),
        ).knowingAddress()
    }

    /**
     * Улики, потерявшие свой файл — записанные до #687 (путь никогда не копировался) или
     * переживающие сбой копирования, — не воскресают: висящий путь хуже честно забытого факта.
     * Не воскресают и пометки при них (#1242): «прочитано сильнее» без самого прочтения
     * отправляет в «или» первое же чтение объекта, и после «Недавнего» текста у него нет вовсе.
     */
    private fun restoredMetadata(entry: HistoryEntry, size: Long): Map<String, String> {
        val alive = REF_KEYS.fold(entry.metadata) { acc, key ->
            val value = acc[key]
            if (value != null && !File(value).isFile) withoutKnowledge(acc, setOf(key)) else acc
        }
        return buildMap {
            putAll(alive)
            entry.name?.let { put("name", it) }
            put(META_SIZE, size.toString())
        }
    }

    override suspend fun remove(entryId: String): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            val entries = readEntries()
            val entry = entries[entryId] ?: return@withLock
            forget(entry)
            rewriteIndex(entries.values.filterNot { it.id == entryId })
        }
    }

    override suspend fun clearAll() {
        withContext(Dispatchers.IO) { mutex.withLock { dir.deleteRecursively() } }
    }

    /**
     * Считается то, что лежит на диске, а не то, что записано в перечне (#821): рядом с
     * копией объекта живут копии улик, и место занимают именно они.
     */
    override suspend fun footprint(): HistoryFootprint = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching {
                HistoryFootprint(
                    count = readEntries().size,
                    bytes = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() },
                )
            }.getOrDefault(HistoryFootprint(0, 0L))
        }
    }

    /**
     * Запись оставляет на диске не только копию объекта, но и копии улик рядом с ней
     * (`<id>.ocr-text-ref` и подобные, см. [copyEvidence]). Уходит запись — уходит всё её: иначе
     * распознанный текст переживает и «Убрать», и вытеснение по лимиту, а Point обещал обратное.
     */
    private fun forget(entry: HistoryEntry) {
        runCatching { File(entry.ref.value).delete() }
        REF_KEYS.forEach { key -> runCatching { evidenceFile(entry.id, key).delete() } }
    }

    private fun rewriteIndex(entries: List<HistoryEntry>) {
        index.writeText(
            entries.joinToString("") {
                row(it.id, it.mime, it.kind.name, it.name, it.epochMillis, it.ref.value, it.features, it.metadata) + "\n"
            },
        )
    }

    private fun pruneToLimit() {
        val entries = readEntries().values.toList()
        if (entries.size <= MAX_ENTRIES) return
        entries.dropLast(MAX_ENTRIES).forEach { forget(it) }
        rewriteIndex(entries.takeLast(MAX_ENTRIES))
    }

    private fun row(
        id: String,
        mime: String,
        kind: String,
        name: String?,
        t: Long,
        path: String,
        features: Set<Feature>,
        metadata: Map<String, String>,
    ): String = JSONObject()
        .put("id", id)
        .put("mime", mime)
        .put("kind", kind)
        // Имени нет — ключ не пишем вовсе (#1437): `JSONObject.NULL` читался обратно строкой «null»,
        // а сама строка «null» именем быть не может (её и роняем, не цементируем дальше).
        .apply { if (!name.isNullOrBlank() && !name.equals("null", ignoreCase = true)) put("name", name) }
        .put("t", t)
        .put("path", path)
        .put("features", JSONArray(features.map { it.name }))
        .put("meta", JSONObject(metadata))
        .toString()

    private fun readEntries(): Map<String, HistoryEntry> = readIndex().entries

    /** Перечень целиком: сами записи и то, сколькими строками они записаны (#1246). */
    private class Journal(val entries: Map<String, HistoryEntry>, val rows: Int)

    private fun readIndex(): Journal {
        if (!index.exists()) return Journal(emptyMap(), 0)
        val result = LinkedHashMap<String, HistoryEntry>()
        var rows = 0
        index.forEachLine { line ->
            if (line.isBlank()) return@forEachLine
            rows++
            runCatching {
                val json = JSONObject(line)
                val id = json.getString("id")
                result.remove(id)
                result[id] = HistoryEntry(
                    id = id,
                    mime = json.getString("mime"),
                    kind = runCatching { ObjectKind.valueOf(json.getString("kind")) }.getOrDefault(ObjectKind.UNKNOWN),
                    // #1437: у безымянного объекта имя выходило строкой «null». Причин две и обе
                    // тут закрыты: `optString` на записанном `JSONObject.NULL` отдаёт на Android
                    // «null» (в JVM — «»), а уплотнение перечня цементировало это в файл строкой
                    // `"name":"null"`. И пустое, и литерал «null» — «имени нет».
                    name = json.optString("name").trim().takeUnless { it.isEmpty() || it.equals("null", ignoreCase = true) },
                    epochMillis = json.getLong("t"),
                    ref = ScratchRef(json.getString("path")),
                    features = json.optJSONArray("features")?.let { arr ->
                        (0 until arr.length()).mapNotNullTo(mutableSetOf()) { i ->
                            runCatching { Feature.valueOf(arr.getString(i)) }.getOrNull()
                        }
                    } ?: emptySet(),
                    metadata = json.optJSONObject("meta")?.toMetadata() ?: legacyMetadata(json),
                )
            }
        }
        return Journal(result, rows)
    }

    private fun JSONObject.toMetadata(): Map<String, String> =
        keys().asSequence().associateWith { key -> getString(key) }

    /** Журнал до #687 писал только сущности — из них восстанавливается хотя бы это. */
    private fun legacyMetadata(json: JSONObject): Map<String, String> =
        json.optJSONObject("entities")?.toMetadata()
            ?.mapKeys { META_ENTITY_PREFIX + it.key }
            ?: emptyMap()

    private companion object {

        const val MAX_ENTRIES = 50

        /**
         * Сколько строк перечень держит до уплотнения (#1246).
         *
         * Строка на запись — только у нетронутого объекта: каждый визит дописывает знание
         * заново, и живые снимки с телефона дают 51–72 строки на полсотни записей. Три
         * строки на запись — тот же обещанный полтинник, только без вечного роста.
         */
        const val MAX_ROWS = MAX_ENTRIES * 3

        val REF_KEYS = setOf(META_OCR_TEXT_REF, META_OCR_ATOMS_REF)
    }
}
