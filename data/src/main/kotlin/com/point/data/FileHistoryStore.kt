package com.point.data

import com.point.core.flow.HistoryStore
import com.point.core.flow.META_ENTITY_PREFIX
import com.point.core.flow.META_SIZE
import com.point.core.flow.ObjectClassifier
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
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * File-based history: a persistent copy of each object plus an append-only
 * `index.jsonl` journal. No Room — a plain, unit-testable store. The base dir is
 * `filesDir/history`, which the scratch wipe never touches; it is injected so the
 * store is testable on the JVM. History is bounded to [MAX_ENTRIES] most-recent
 * objects so it never silently fills the device (#8); writes are serialised by a
 * mutex (app-scoped @Singleton) because pruning read-rewrites the journal.
 */
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
        val ext = extensionFor(name, obj.mime)
        val dest = File(dir, if (ext.isBlank()) obj.id else "${obj.id}.$ext")
        mutex.withLock {
            source.inputStream().use { input -> dest.outputStream().use { input.copyTo(it) } }
            index.appendText(
                row(
                    obj.id, obj.mime, obj.state.kind.name, name, System.currentTimeMillis(),
                    dest.absolutePath, obj.state.features, entityValues(obj.metadata),
                ) + "\n",
            )
            pruneToLimit() // keep history bounded so it never silently fills the disk (#8)
        }
    }

    /** Append a fresh journal line for the id — last line wins, so the entry now carries
     *  what enrichment understood (#114). The persisted copy and timestamp stay as-is. */
    override suspend fun update(obj: PointObject): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            val existing = readEntries()[obj.id] ?: return@withLock
            index.appendText(
                row(
                    existing.id, existing.mime, existing.kind.name, existing.name, existing.epochMillis,
                    existing.ref.value, obj.state.features, entityValues(obj.metadata),
                ) + "\n",
            )
        }
    }

    /** The `entity.*` understood facts, keyed without the prefix (phone → «+380…»). */
    private fun entityValues(metadata: Map<String, String>): Map<String, String> =
        metadata.filterKeys { it.startsWith(META_ENTITY_PREFIX) }
            .mapKeys { it.key.removePrefix(META_ENTITY_PREFIX) }

    override suspend fun recent(limit: Int): List<HistoryEntry> = withContext(Dispatchers.IO) {
        // Order by journal recency, not by the millisecond timestamp: several records
        // in the same millisecond tie on `t` and a stable sort then falls back to
        // insertion (oldest-first) order — wrong, and flaky on a fast machine.
        // readEntries() yields entries oldest→newest (latest occurrence last), so reverse.
        readEntries().values
            .filter { File(it.ref.value).exists() }
            .reversed()
            .take(limit)
    }

    /**
     * Переоткрыть объект из «Недавнего» — вместе с тем, что о нём УЖЕ поняли (#532).
     *
     * Раньше отсюда уезжали только имя и вес, а признаки и найденные сущности выбрасывались —
     * хотя лежали в том же журнале, строкой выше. Цена этому — живой замер: переоткрытие того же
     * чека снова показывало «Распознаю текст…» двадцать-тридцать секунд, и все эти секунды объект
     * стоял голым, без «Позвонить» и «Создать событие», которые Point про него уже знал.
     * `MetadataEntityEnricher` зажигает такие сущности мгновенно и без единого чтения диска, так
     * что первый экран остаётся в своём бюджете (I/O здесь — тот же, что и был: длина файла).
     *
     * **Что НЕ возвращается — и почему.** [Feature.HAS_TEXT] и [Feature.HAS_WORD_LAYER] говорят не
     * о самом объекте, а о том, что рядом лежит написанный распознаванием файл (`META_OCR_TEXT_REF`,
     * `META_OCR_ATOMS_REF`). Файлы эти живут в scratch и стираются по окончании работы, а в журнал
     * не попадают вовсе (см. [entityValues] — журналятся только `entity.*`). Вернуть признак без
     * улики значило бы предложить «Найти в документе» странице, на которой искать не по чему, —
     * ровно та ложь, от которой `REFRESHABLE_META` бережёт живой флоу.
     */
    override suspend fun open(entryId: String): PointObject? = withContext(Dispatchers.IO) {
        val entry = readEntries()[entryId] ?: return@withContext null
        val file = File(entry.ref.value)
        if (!file.exists()) return@withContext null
        val size = file.length()
        val fresh = classifier.classify(entry.mime, size, file.name)
        PointObject(
            id = UUID.randomUUID().toString(),
            mime = entry.mime,
            uri = ScratchRef(file.absolutePath),
            state = entry.features.filter { it !in EVIDENCE_BACKED }
                .fold(fresh) { state, feature -> state.with(feature) },
            // Переоткрытый из «Недавнего» объект меряется так же, как только что расшаренный
            // (#459): вес уже взят здесь, на фоне, до всякого экрана.
            metadata = buildMap {
                entry.name?.let { put("name", it) }
                put(META_SIZE, size.toString())
                entry.entities.forEach { (key, value) -> put(META_ENTITY_PREFIX + key, value) }
            },
        )
    }

    override suspend fun clearAll() {
        withContext(Dispatchers.IO) { mutex.withLock { dir.deleteRecursively() } }
    }

    /**
     * Cap history at [MAX_ENTRIES] most-recent objects: delete the evicted copies and
     * rewrite the journal to one compact line per survivor. Without this the store grows
     * forever — every shared object left a file behind (#8). Called under [mutex].
     */
    private fun pruneToLimit() {
        val entries = readEntries().values.toList() // oldest → newest
        if (entries.size <= MAX_ENTRIES) return
        entries.dropLast(MAX_ENTRIES).forEach { runCatching { File(it.ref.value).delete() } }
        val survivors = entries.takeLast(MAX_ENTRIES)
        index.writeText(
            survivors.joinToString("") {
                row(it.id, it.mime, it.kind.name, it.name, it.epochMillis, it.ref.value, it.features, it.entities) + "\n"
            },
        )
    }

    private fun row(
        id: String,
        mime: String,
        kind: String,
        name: String?,
        t: Long,
        path: String,
        features: Set<Feature>,
        entities: Map<String, String>,
    ): String = JSONObject()
        .put("id", id)
        .put("mime", mime)
        .put("kind", kind)
        .put("name", name ?: JSONObject.NULL)
        .put("t", t)
        .put("path", path)
        .put("features", JSONArray(features.map { it.name }))
        .put("entities", JSONObject(entities))
        .toString()

    /** id -> latest entry (last journal line for that id wins). */
    private fun readEntries(): Map<String, HistoryEntry> {
        if (!index.exists()) return emptyMap()
        val result = LinkedHashMap<String, HistoryEntry>()
        index.forEachLine { line ->
            if (line.isBlank()) return@forEachLine
            runCatching {
                val json = JSONObject(line)
                val id = json.getString("id")
                result.remove(id) // re-record moves the id to the end: latest occurrence = newest
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
                    entities = json.optJSONObject("entities")?.let { obj ->
                        obj.keys().asSequence().associateWith { key -> obj.getString(key) }
                    } ?: emptyMap(),
                )
            }
        }
        return result
    }

    /**
     * Расширение копии в истории.
     *
     * Имя объекта с #533 — это фраза из его содержимого («Пришлите договор до пятницы», «Запись,
     * 4 авг 19:25»), а не имя файла. Хвост после последней точки в такой фразе расширением не
     * является: «1.5 кг сахара» дало бы копию `<id>.5 кг сахара`, а «и/или» — путь в несуществующую
     * папку и потерянную запись истории. Поэтому из имени берётся только то, что расширением
     * выглядит; всё остальное решает тип.
     */
    private fun extensionFor(name: String?, mime: String): String {
        val fromName = name?.substringAfterLast('.', "")?.lowercase().orEmpty()
        if (fromName.isNotBlank() && fromName.length <= MAX_EXT && fromName.all { it.isLetterOrDigit() }) {
            return fromName
        }
        return when {
            mime.startsWith("image/") -> mime.substringAfter('/').substringBefore('+')
            mime == "application/pdf" -> "pdf"
            mime == "text/markdown" -> "md"
            mime.startsWith("text/") -> "txt"
            mime == "application/zip" -> "zip"
            else -> ""
        }
    }

    private companion object {
        /** Keep the last N objects — more than the 30 Home shows, so nothing recent is lost. */
        const val MAX_ENTRIES = 50

        /** Длиннее расширений не бывает у того, что Point принимает: `jpeg`, `xlsx`, `webp`. */
        const val MAX_EXT = 5

        /** Признаки, за которыми стоит написанный файл, а не свойство объекта (#532). Живут они в
         *  scratch, стираются вместе с работой — и вернуться из журнала не могут по определению. */
        val EVIDENCE_BACKED = setOf(Feature.HAS_TEXT, Feature.HAS_WORD_LAYER)
    }
}
