package com.point.data

import com.point.core.flow.FlowSnapshotStore
import com.point.core.model.FlowSnapshotFrame
import com.point.core.model.ObjectKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton
import java.io.File

/** One small JSON file (injected, outside scratch) — plain, unit-testable, corruption-safe. */
@Singleton
class FileFlowSnapshotStore @Inject constructor(
    @com.point.data.di.FlowSnapshotFile private val file: File,
) : FlowSnapshotStore {

    override suspend fun save(frames: List<FlowSnapshotFrame>): Unit = withContext(Dispatchers.IO) {
        runCatching {
            val array = JSONArray()
            frames.forEach { f ->
                array.put(
                    JSONObject()
                        .put("id", f.id)
                        .put("kind", f.kind.name)
                        .put("mime", f.mime)
                        .put("ref", f.ref)
                        .put("metadata", JSONObject(f.metadata))
                        .put("via", f.viaCapabilityId ?: JSONObject.NULL)
                        .put("viaTitle", f.viaTitle ?: JSONObject.NULL),
                )
            }
            file.writeText(array.toString())
        }
    }

    override suspend fun load(): List<FlowSnapshotFrame> = withContext(Dispatchers.IO) {
        runCatching {
            if (!file.exists()) return@withContext emptyList()
            val array = JSONArray(file.readText())
            buildList {
                for (i in 0 until array.length()) {
                    val o = array.getJSONObject(i)
                    add(
                        FlowSnapshotFrame(
                            id = o.getString("id"),
                            kind = runCatching { ObjectKind.valueOf(o.getString("kind")) }
                                .getOrDefault(ObjectKind.UNKNOWN),
                            mime = o.getString("mime"),
                            ref = o.getString("ref"),
                            metadata = o.optJSONObject("metadata")?.let { m ->
                                m.keys().asSequence().associateWith { m.getString(it) }
                            } ?: emptyMap(),
                            viaCapabilityId = o.optString("via").ifBlank { null },
                            viaTitle = o.optString("viaTitle").ifBlank { null },
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    override suspend fun clear(): Unit = withContext(Dispatchers.IO) {
        runCatching { file.delete() }
    }
}
