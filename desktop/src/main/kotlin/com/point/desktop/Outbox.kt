package com.point.desktop

import com.point.core.flow.PcOutboxEntry
import com.point.core.flow.decodePcMeta
import com.point.core.flow.encodePcMeta
import com.point.core.model.PointObject
import java.io.File

class Outbox(private val dir: File) {

    @Synchronized
    fun add(obj: PointObject): Int {
        dir.mkdirs()
        val id = nextId()
        File(obj.uri.value).copyTo(File(dir, "$id.bin"), overwrite = false)
        val meta = packedForTravel(obj.metadata) + mapOf(
            "name" to (obj.metadata["name"] ?: File(obj.uri.value).name),
            "mime" to obj.mime,
        )
        File(dir, "$id.meta").writeText(encodePcMeta(meta))
        return id
    }

    /**
     * Исход без объекта (#1073): запись из одних слов, файла у неё нет.
     *
     * Так телефону доезжает поздний исход его просьбы — «Отменено» у диалога сохранения,
     * отказ принтера, — которому прежде в очереди было нечего положить: она знала только вещи.
     */
    @Synchronized
    fun addOutcome(meta: Map<String, String>): Int {
        dir.mkdirs()
        val id = nextId()
        File(dir, "$id.meta").writeText(encodePcMeta(meta))
        return id
    }

    private fun nextId(): Int = (ids().maxOrNull() ?: 0) + 1

    @Synchronized
    fun entries(): List<PcOutboxEntry> = ids().sorted().mapNotNull { id ->
        runCatching { PcOutboxEntry(id, decodePcMeta(File(dir, "$id.meta").readText())) }.getOrNull()
    }

    fun file(id: Int): File? = File(dir, "$id.bin").takeIf(File::isFile)

    /**
     * Забыть то, за чем человек так и не пришёл (#1317, решение владельца 29.08.2026, вариант A).
     *
     * Уйти из очереди можно было ровно одним способом — телефон подтвердил, что забрал. Всё
     * остальное лежало вечно: вещь, за которой не пришли, и запись-исход, чей объект человек
     * больше никогда не откроет. Восемь таких записей на машине владельца превращались в
     * «телефон предлагает забрать 7 объектов» после одной отправки.
     *
     * Срок берётся не новый: тот же `COPY_LIFETIME_MS`, что у копии объекта на телефоне, — и
     * одно правило на вещи и на записи-исходы, потому что для человека это одинаково брошенное.
     * [before] — момент, старше которого запись брошена; возвращает, сколько убрано.
     */
    @Synchronized
    fun forgetOlderThan(before: Long): Int {
        val abandoned = ids().filter { File(dir, "$it.meta").lastModified() < before }
        abandoned.forEach(::remove)
        return abandoned.size
    }

    @Synchronized
    fun remove(id: Int) {
        File(dir, "$id.meta").delete()
        File(dir, "$id.bin").delete()
    }

    private fun ids(): List<Int> =
        dir.listFiles()?.mapNotNull { it.name.removeSuffix(".meta").toIntOrNull().takeIf { _ -> it.name.endsWith(".meta") } }
            ?: emptyList()
}
