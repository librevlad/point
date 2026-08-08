package com.point.data

import com.point.core.flow.FlowSnapshotStore
import com.point.core.model.FlowSnapshotFrame
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.Relation
import com.point.core.model.RelationType
import com.point.core.model.ScratchRef
import com.point.core.model.ValueRef
import com.point.core.model.isFileBacked
import com.point.core.model.provenanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton
import java.io.File

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
                        .put("viaTitle", f.viaTitle ?: JSONObject.NULL)
                        .put("found", JSONArray().also { arr -> f.found.forEach { arr.put(foundJson(it)) } })
                        .put(
                            "relations",
                            JSONArray().also { arr ->
                                f.relations.forEach {
                                    arr.put(
                                        JSONObject()
                                            .put("from", it.fromId)
                                            .put("type", it.type.name)
                                            .put("to", it.toId),
                                    )
                                }
                            },
                        )
                        .put("focusRegion", f.focusRegion ?: JSONObject.NULL)
                        .put("focusIds", f.focusIds ?: JSONObject.NULL),
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

                            found = o.optJSONArray("found")?.let(::foundOf).orEmpty(),
                            relations = o.optJSONArray("relations")?.let(::relationsOf).orEmpty(),
                            focusRegion = o.optString("focusRegion").ifBlank { null },
                            focusIds = o.optString("focusIds").ifBlank { null },
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    override suspend fun clear(): Unit = withContext(Dispatchers.IO) {
        runCatching { file.delete() }
    }

    private fun foundJson(obj: PointObject) = JSONObject()
        .put("id", obj.id)
        .put("kind", obj.state.kind.name)
        .put("mime", obj.mime)
        .put("ref", obj.uri.value)
        .put("metadata", JSONObject(obj.metadata))
        .put("provenance", obj.provenance.wire)
        .put("sources", JSONArray(obj.sourceObjects))
        .put("creator", obj.creatorAction ?: JSONObject.NULL)

    /**
     * Найденный объект восстанавливается целиком: знание, происхождение, связь с источником.
     * File-backed объект с умершим payload остаётся value-backed — semantic knowledge
     * переживает удаление payload (ADR-0001 §20).
     */
    private fun foundOf(array: JSONArray): List<PointObject> = buildList {
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val id = o.optString("id").takeIf { it.isNotBlank() } ?: continue
            val kind = ObjectKind.valueOf(o.optString("kind").ifBlank { ObjectKind.UNKNOWN.name })
            val ref = o.optString("ref")
            add(
                PointObject(
                    id = id,
                    mime = o.optString("mime").ifBlank { "text/plain" },
                    uri = if (kind.isFileBacked && File(ref).isFile) ScratchRef(ref) else ValueRef(ref),
                    state = ObjectState(kind),
                    metadata = o.optJSONObject("metadata")?.let { m ->
                        m.keys().asSequence().associateWith { m.getString(it) }
                    } ?: emptyMap(),
                    provenance = provenanceOf(o.optString("provenance").ifBlank { null }),
                    sourceObjects = o.optJSONArray("sources")?.let { s ->
                        (0 until s.length()).map { s.getString(it) }
                    } ?: emptyList(),
                    creatorAction = o.optString("creator").ifBlank { null },
                ),
            )
        }
    }

    private fun relationsOf(array: JSONArray): List<Relation> = buildList {
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val from = o.optString("from").takeIf { it.isNotBlank() } ?: continue
            val to = o.optString("to").takeIf { it.isNotBlank() } ?: continue
            val type = o.optString("type").takeIf { it.isNotBlank() } ?: continue
            add(Relation(from, RelationType(type), to))
        }
    }
}
