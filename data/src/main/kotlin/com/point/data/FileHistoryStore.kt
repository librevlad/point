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
            pruneToLimit()
        }
    }

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

    private fun entityValues(metadata: Map<String, String>): Map<String, String> =
        metadata.filterKeys { it.startsWith(META_ENTITY_PREFIX) }
            .mapKeys { it.key.removePrefix(META_ENTITY_PREFIX) }

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
        val fresh = classifier.classify(entry.mime, size, file.name)
        PointObject(
            id = UUID.randomUUID().toString(),
            mime = entry.mime,
            uri = ScratchRef(file.absolutePath),
            state = entry.features.filter { it !in EVIDENCE_BACKED }
                .fold(fresh) { state, feature -> state.with(feature) },

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

    private fun pruneToLimit() {
        val entries = readEntries().values.toList()
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

        const val MAX_ENTRIES = 50

        const val MAX_EXT = 5

        val EVIDENCE_BACKED = setOf(Feature.HAS_TEXT, Feature.HAS_WORD_LAYER)
    }
}
