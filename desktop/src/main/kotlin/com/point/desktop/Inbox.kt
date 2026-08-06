package com.point.desktop

import com.point.core.flow.ObjectClassifier
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import java.io.File
import java.io.InputStream
import java.util.UUID

/** One received or dropped object on the desktop. */
data class InboxItem(
    val obj: PointObject,
    val receivedAt: Long = System.currentTimeMillis(),
)

/** A safe child name inside the inbox: separators/traversal flattened, collisions counted. */
fun uniqueChildName(existing: Set<String>, desired: String): String {
    val flat = desired.replace('/', '_').replace('\\', '_').trim()
    val base = if (flat.isBlank()) "объект" else flat
    if (base !in existing) return base
    val stem = base.substringBeforeLast('.', base)
    val ext = base.substringAfterLast('.', "")
    var n = 2
    while (true) {
        val candidate = if (ext.isEmpty()) "$stem ($n)" else "$stem ($n).$ext"
        if (candidate !in existing) return candidate
        n++
    }
}

/**
 * The desktop's landing zone (`~/Point` by default): objects from the phone stream
 * into files here; local drops are wrapped IN PLACE (no copy — the user's file is
 * already on this machine). Kinds come from the shared [ObjectClassifier].
 */
class Inbox(private val dir: File) {

    private val classifier = ObjectClassifier()

    fun receive(name: String, mime: String, meta: Map<String, String>, source: InputStream): InboxItem {
        dir.mkdirs()
        val safe = uniqueChildName(dir.list()?.toSet() ?: emptySet(), name)
        val target = File(dir, safe)
        val part = File(dir, "$safe.part")
        part.outputStream().use { out -> source.copyTo(out) }
        part.renameTo(target)
        return wrap(target, mime, meta + ("name" to safe))
    }

    fun addText(text: String): InboxItem {
        dir.mkdirs()
        val safe = uniqueChildName(dir.list()?.toSet() ?: emptySet(), "Заметка.txt")
        val target = File(dir, safe)
        target.writeText(text)
        return wrap(target, "text/plain", mapOf("name" to safe))
    }

    fun addFile(path: String): InboxItem {
        val f = File(path)
        return wrap(f, mimeFor(f.name), mapOf("name" to f.name))
    }

    /**
     * Убрать то, что пролежало дольше срока (#602).
     *
     * На телефоне рабочая копия стирается по окончании работы — это инвариант. На компьютере
     * такого правила не было вовсе: у владельца в этой папке лежали 23 файла за 11 дней, включая
     * снимок приложения-аутентификатора с кодами и переписку. Через эту дверь идёт то же, что
     * через телефон, — чеки, переписка, коды; разница была только в том, что здесь они оставались.
     *
     * Убирается **только то, что Point сюда положил сам**: присланное с телефона, снимки экрана,
     * скачанное, заметки из буфера. Файл, который человек перетащил мышью, здесь не лежит вовсе —
     * он остаётся там, где был, и Point его не трогает никогда.
     *
     * Срок тот же, что у сервера: сутки. Возвращает, сколько убрано.
     */
    fun sweep(olderThan: Long): Int {
        var removed = 0
        listOf(dir, File(dir, "screens"), File(dir, "downloads")).forEach { where ->
            where.listFiles()?.forEach { f ->
                if (f.isFile && f.lastModified() < olderThan && runCatching { f.delete() }.getOrDefault(false)) {
                    removed++
                }
            }
        }
        return removed
    }

    /** «Выйти» — унести всё: и файлы, и подпапки. Аккаунт ушёл, значит и следы его работы. */
    fun wipe() {
        dir.listFiles()?.forEach { runCatching { it.deleteRecursively() } }
    }

    private fun wrap(file: File, mime: String, meta: Map<String, String>): InboxItem {
        val state = classifier.classify(mime, file.length(), file.name)
        return InboxItem(
            PointObject(
                id = UUID.randomUUID().toString(),
                mime = mime,
                uri = ScratchRef(file.absolutePath),
                state = state,
                metadata = meta,
            ),
        )
    }
}
