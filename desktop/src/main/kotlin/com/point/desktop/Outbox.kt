package com.point.desktop

import com.point.core.flow.PcOutboxEntry
import com.point.core.flow.decodePcMeta
import com.point.core.flow.encodePcMeta
import com.point.core.model.PointObject
import java.io.File

/**
 * The PC's outbox (#161): a transit queue the phone pulls. Each entry is a pair
 * `<n>.bin` + `<n>.meta` — the display name and mime live INSIDE the meta, so the
 * directory needs no name sanitation and download is a direct id lookup. [remove]
 * deletes the meta FIRST: the meta is the existence marker, so a crash in between
 * leaves a harmless orphan `.bin`, never a listed-but-gone entry.
 */
class Outbox(private val dir: File) {

    /** Copy the object's bytes + understanding into the queue. Returns the entry id. */
    @Synchronized
    fun add(obj: PointObject): Int {
        dir.mkdirs()
        val id = (ids().maxOrNull() ?: 0) + 1
        File(obj.uri.value).copyTo(File(dir, "$id.bin"), overwrite = false)
        val meta = obj.metadata + mapOf(
            "name" to (obj.metadata["name"] ?: File(obj.uri.value).name),
            "mime" to obj.mime,
        )
        File(dir, "$id.meta").writeText(encodePcMeta(meta))
        return id
    }

    @Synchronized
    fun entries(): List<PcOutboxEntry> = ids().sorted().mapNotNull { id ->
        runCatching { PcOutboxEntry(id, decodePcMeta(File(dir, "$id.meta").readText())) }.getOrNull()
    }

    /** The entry's bytes, or null for a stranger id (also the path-traversal guard). */
    fun file(id: Int): File? = File(dir, "$id.bin").takeIf(File::isFile)

    @Synchronized
    fun remove(id: Int) {
        File(dir, "$id.meta").delete()
        File(dir, "$id.bin").delete()
    }

    private fun ids(): List<Int> =
        dir.listFiles()?.mapNotNull { it.name.removeSuffix(".meta").toIntOrNull().takeIf { _ -> it.name.endsWith(".meta") } }
            ?: emptyList()
}
