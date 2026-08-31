package com.point.desktop

import com.point.core.flow.PcOutboxEntry
import com.point.core.flow.decodePcMeta
import com.point.core.flow.encodePcMeta
import com.point.core.model.PointObject
import java.io.File

class Outbox(
    private val dir: File,

    /**
     * Кому сказать, что запись ушла из очереди (#1336, #1344).
     *
     * Шов один на обе дороги — забор телефоном и уборку по сроку, — потому что вопрос у
     * человека один: «что стало с моей просьбой». Отличается ответ, и его называет [taken].
     */
    private val onGone: (id: Int, taken: Boolean) -> Unit = { _, _ -> },
) {

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

    /**
     * Номер занят, пока в папке лежит хоть один его файл (#1317).
     *
     * Считался он по одним записям — а байты свою запись переживают: `add` кладёт `.bin` и
     * `.meta` двумя движениями, `remove` двумя же их убирает, и между движениями бывает и
     * смерть процесса, и Windows, не отдавшая файл. Номер, у которого остался один `.bin`,
     * для счёта по записям свободен — и следующая отправка упиралась в него насмерть: чужие
     * байты `add` не перезаписывает, а номер дальше не сдвигался, и каждое «На телефон»
     * отвечало «Не удалось отправить», пока файл не уберут руками. С уборкой очереди
     * вернуться к брошенному номеру стало обычным делом: очередь пустеет при каждом запуске.
     */
    private fun nextId(): Int = (numbered().keys.maxOrNull() ?: 0) + 1

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
     *
     * Брошенное считается по тому, что лежит в папке, а не по одним записям: байты, оставшиеся
     * без своей записи, — те же байты объекта человека, и убрать их больше некому — уборка
     * папки `~/Point` внутрь очереди не заходит. Номер уходит целиком и судится по самому
     * свежему своему файлу. [before] — момент, старше которого лежащее брошено.
     */
    @Synchronized
    fun forgetOlderThan(before: Long) {
        numbered()
            .filterValues { files -> files.all { it.lastModified() < before } }
            .keys.forEach { id ->
                drop(id)
                runCatching { onGone(id, false) }
            }
    }

    @Synchronized
    fun remove(id: Int) {
        drop(id)
        runCatching { onGone(id, true) }
    }

    private fun drop(id: Int) {
        File(dir, "$id.meta").delete()
        File(dir, "$id.bin").delete()
    }

    /** Записи очереди — по `.meta`: запись это слова, а файл у неё бывает, а бывает нет (#1073). */
    private fun ids(): List<Int> = dir.listFiles().orEmpty()
        .filter { it.name.endsWith(".meta") }
        .mapNotNull { it.name.removeSuffix(".meta").toIntOrNull() }

    /** Всё, что лежит в папке номером: слова записи, байты вещи — и то, что пережило пару. */
    private fun numbered(): Map<Int, List<File>> = dir.listFiles().orEmpty()
        .mapNotNull { file -> numberOf(file)?.let { it to file } }
        .groupBy({ it.first }, { it.second })

    private fun numberOf(file: File): Int? = when {
        file.name.endsWith(".meta") -> file.name.removeSuffix(".meta").toIntOrNull()
        file.name.endsWith(".bin") -> file.name.removeSuffix(".bin").toIntOrNull()
        else -> null
    }
}
