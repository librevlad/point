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

/**
 * Пригодное имя файла для объекта, названного по-человечески (#603).
 *
 * Человеческое имя приходит с телефона и годится для экрана, но не для диска: в нём бывает
 * многоточие, двоеточие и прочие знаки, которых файловая система не берёт, нет расширения, и
 * длина у него любая. Файл без расширения не открывается двойным щелчком — это и была жалоба.
 *
 * Здесь имя приводится к пригодному: запрещённые знаки убираются, длина ограничивается,
 * расширение берётся у типа объекта — компьютер его знает, оно приехало вместе с объектом.
 */
fun fileNameFor(humanName: String, mime: String): String {
    val cleaned = humanName
        .replace(FORBIDDEN, " ")
        .replace(Regex("""\s+"""), " ")
        .trim(' ', '.')
        .take(MAX_NAME)
        .trim(' ', '.')
    val base = cleaned.ifBlank { "объект" }
    val known = base.substringAfterLast('.', "").lowercase()
    val wanted = extensionFor(mime)
    // Расширение уже на месте — второго не приписываем: «отчёт.pdf.pdf» это не аккуратность.
    return if (wanted.isEmpty() || known == wanted) base else "$base.$wanted"
}

/** Знаки, которых не берёт файловая система Windows (остальные системы берут меньше). */
private val FORBIDDEN = Regex("""[\\/:*?"<>|…\n\r\t]""")

/** Столько имени хватает человеку и не упирается в предел пути. */
private const val MAX_NAME = 80

/** Расширение по типу объекта: компьютер его знает — тип приехал вместе с объектом. */
private fun extensionFor(mime: String): String = when (mime.substringBefore(';').trim()) {
    "text/plain" -> "txt"
    "text/uri-list" -> "txt"
    "image/png" -> "png"
    "image/jpeg" -> "jpg"
    "image/webp" -> "webp"
    "application/pdf" -> "pdf"
    "application/zip" -> "zip"
    "audio/mpeg" -> "mp3"
    "audio/mp4", "audio/m4a" -> "m4a"
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> "docx"
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> "xlsx"
    "application/vnd.openxmlformats-officedocument.presentationml.presentation" -> "pptx"
    else -> ""
}

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

    /**
     * Приехавшее с телефона (#603).
     *
     * Имя объекта и имя файла — разные вещи, и раньше они были одним. Телефон называет объекты по
     * содержимому («Счёт 4512 от ООО Ромашка. Оплатить до 20…»), а компьютер брал это имя для
     * файла: отсюда многоточие внутри имени, отсутствие расширения — такой файл не открывается
     * двойным щелчком — и «(2)» из уникализации, попадавшее человеку на экран как часть названия.
     *
     * Теперь на экране остаётся то, что прислал телефон, дословно, а на диске лежит пригодное имя.
     */
    fun receive(name: String, mime: String, meta: Map<String, String>, source: InputStream): InboxItem {
        dir.mkdirs()
        val safe = uniqueChildName(dir.list()?.toSet() ?: emptySet(), fileNameFor(name, mime))
        val target = File(dir, safe)
        val part = File(dir, "$safe.part")
        part.outputStream().use { out -> source.copyTo(out) }
        part.renameTo(target)
        return wrap(target, mime, meta + ("name" to name))
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
