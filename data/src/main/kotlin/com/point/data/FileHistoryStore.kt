package com.point.data

import com.point.core.flow.HistoryStore
import com.point.core.flow.META_ENTITY_PREFIX
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

    override suspend fun open(entryId: String): PointObject? = withContext(Dispatchers.IO) {
        val entry = readEntries()[entryId] ?: return@withContext null
        val file = File(entry.ref.value)
        if (!file.exists()) return@withContext null
        PointObject(
            id = UUID.randomUUID().toString(),
            mime = entry.mime,
            uri = ScratchRef(file.absolutePath),
            state = classifier.classify(entry.mime, file.length(), entry.name),
            metadata = buildMap { entry.name?.let { put("name", it) } },
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

    private fun extensionFor(name: String?, mime: String): String {
        val fromName = name?.substringAfterLast('.', "")?.lowercase().orEmpty()
        if (fromName.isNotBlank()) return fromName
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
    }
}
