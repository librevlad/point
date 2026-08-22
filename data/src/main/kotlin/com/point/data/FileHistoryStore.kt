package com.point.data

import com.point.core.flow.HistoryFootprint
import com.point.core.flow.HistoryStore
import com.point.core.flow.extensionForFile
import com.point.core.flow.META_CLOUD_ATOMS_REF
import com.point.core.flow.META_ENTITY_PREFIX
import com.point.core.flow.META_OCR_ATOMS_REF
import com.point.core.flow.META_OCR_TEXT_REF
import com.point.core.flow.META_SIZE
import com.point.core.flow.ObjectClassifier
import com.point.core.flow.knowingAddress
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
            val existing = readEntries()[obj.id] ?: return@withLock
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
     * факта.
     */
    private fun persistedMetadata(id: String, metadata: Map<String, String>): Map<String, String> =
        REF_KEYS.fold(metadata) { acc, key ->
            val value = acc[key]
            if (value.isNullOrBlank()) return@fold acc
            val copied = copyEvidence(id, key, value)
            if (copied != null) acc + (key to copied) else acc - key
        }

    private fun copyEvidence(id: String, key: String, path: String): String? {
        val source = File(path).takeIf { it.isFile } ?: return null
        val dest = evidenceFile(id, key)
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
     */
    private fun restoredMetadata(entry: HistoryEntry, size: Long): Map<String, String> {
        val alive = REF_KEYS.fold(entry.metadata) { acc, key ->
            val value = acc[key]
            if (value != null && !File(value).isFile) acc - key else acc
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
        .put("name", name ?: JSONObject.NULL)
        .put("t", t)
        .put("path", path)
        .put("features", JSONArray(features.map { it.name }))
        .put("meta", JSONObject(metadata))
        .toString()

    private fun readEntries(): Map<String, HistoryEntry> {
        if (!index.exists()) return emptyMap()
        val result = LinkedHashMap<String, HistoryEntry>()
        index.forEachLine { line ->
            if (line.isBlank()) return@forEachLine
            runCatching {
                val json = JSONObject(line)
                val id = json.getString("id")
                result.remove(id)
                result[id] = HistoryEntry(
                    id = id,
                    mime = json.getString("mime"),
                    kind = runCatching { ObjectKind.valueOf(json.getString("kind")) }.getOrDefault(ObjectKind.UNKNOWN),
                    name = json.optString("name").ifBlank { null },
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
        return result
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


        val REF_KEYS = setOf(META_OCR_TEXT_REF, META_OCR_ATOMS_REF, META_CLOUD_ATOMS_REF)
    }
}
