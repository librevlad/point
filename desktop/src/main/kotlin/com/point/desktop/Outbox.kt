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
        val meta = travellingMeta(obj) + mapOf(
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

    @Synchronized
    fun remove(id: Int) {
        File(dir, "$id.meta").delete()
        File(dir, "$id.bin").delete()
    }

    /**
     * Знание едет значением (#811, ADR-0001 §20).
     *
     * Прочитанный текст лежит файлом на этом компьютере, и ссылка на него на телефоне ведёт
     * в никуда: там объект снова выглядел непрочитанным. Телефон в обратную сторону это уже
     * умел — компьютер отвечал ссылкой.
     */
    private fun travellingMeta(obj: PointObject): Map<String, String> {
        val ref = obj.metadata[com.point.core.flow.META_OCR_TEXT_REF]?.takeIf { it.isNotBlank() }
            ?: return obj.metadata
        val text = runCatching {
            File(ref).takeIf(File::isFile)?.readText()?.take(com.point.core.flow.READ_TEXT_TRAVEL_LIMIT)
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: return obj.metadata - com.point.core.flow.META_OCR_TEXT_REF

        return obj.metadata - com.point.core.flow.META_OCR_TEXT_REF +
            (com.point.core.flow.META_READ_TEXT to text)
    }

    private fun ids(): List<Int> =
        dir.listFiles()?.mapNotNull { it.name.removeSuffix(".meta").toIntOrNull().takeIf { _ -> it.name.endsWith(".meta") } }
            ?: emptyList()
}
