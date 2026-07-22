package com.point.data

import com.point.core.flow.HistoryStore
import com.point.core.flow.ObjectClassifier
import com.point.core.model.HistoryEntry
import com.point.core.model.ObjectKind
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import com.point.data.di.HistoryDir
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.UUID
import javax.inject.Inject

/**
 * File-based history: a persistent copy of each object plus an append-only
 * `index.jsonl` journal. No Room — a plain, unit-testable store. The base dir is
 * `filesDir/history`, which the scratch wipe never touches; it is injected so the
 * store is testable on the JVM.
 */
class FileHistoryStore @Inject constructor(
    @HistoryDir private val baseDir: File,
    private val classifier: ObjectClassifier,
) : HistoryStore {

    private val dir: File get() = baseDir.apply { mkdirs() }
    private val index: File get() = File(dir, "index.jsonl")

    override suspend fun record(obj: PointObject) = withContext(Dispatchers.IO) {
        val source = File(obj.uri.value)
        if (!source.exists()) return@withContext
        val name = obj.metadata["name"]
        val ext = extensionFor(name, obj.mime)
        val dest = File(dir, if (ext.isBlank()) obj.id else "${obj.id}.$ext")
        source.inputStream().use { input -> dest.outputStream().use { input.copyTo(it) } }

        val row = JSONObject()
            .put("id", obj.id)
            .put("mime", obj.mime)
            .put("kind", obj.state.kind.name)
            .put("name", name ?: JSONObject.NULL)
            .put("t", System.currentTimeMillis())
            .put("path", dest.absolutePath)
        index.appendText(row.toString() + "\n")
    }

    override suspend fun recent(limit: Int): List<HistoryEntry> = withContext(Dispatchers.IO) {
        readEntries().values
            .filter { File(it.ref.value).exists() }
            .sortedByDescending { it.epochMillis }
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
        withContext(Dispatchers.IO) { dir.deleteRecursively() }
    }

    /** id -> latest entry (last journal line for that id wins). */
    private fun readEntries(): Map<String, HistoryEntry> {
        if (!index.exists()) return emptyMap()
        val result = LinkedHashMap<String, HistoryEntry>()
        index.forEachLine { line ->
            if (line.isBlank()) return@forEachLine
            runCatching {
                val json = JSONObject(line)
                val id = json.getString("id")
                result[id] = HistoryEntry(
                    id = id,
                    mime = json.getString("mime"),
                    kind = runCatching { ObjectKind.valueOf(json.getString("kind")) }.getOrDefault(ObjectKind.UNKNOWN),
                    name = json.optString("name").ifBlank { null },
                    epochMillis = json.getLong("t"),
                    ref = ScratchRef(json.getString("path")),
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
}
