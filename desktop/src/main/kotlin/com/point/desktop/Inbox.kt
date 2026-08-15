package com.point.desktop

import com.point.core.flow.EMPTY_FILE_REASON
import com.point.core.flow.META_OCR_TEXT_REF
import com.point.core.flow.META_READ_TEXT
import com.point.core.flow.META_UNUSABLE_REASON
import com.point.core.flow.ObjectClassifier
import com.point.core.model.Feature
import com.point.core.model.ObjectKind
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import java.io.File
import java.io.InputStream
import java.util.UUID

data class InboxItem(
    val obj: PointObject,
    val receivedAt: Long = System.currentTimeMillis(),
)

fun fileNameFor(humanName: String, mime: String): String {
    val base = com.point.core.flow.safeFileName(humanName)
    val known = base.substringAfterLast('.', "").lowercase()
    val wanted = extensionFor(mime)

    return if (wanted.isEmpty() || known == wanted) base else "$base.$wanted"
}



// Расширение спрашивается там же, где его спрашивает `MimeMap`, — в общей таблице (#867).
// Своя таблица знала тринадцать типов и не знала ogg: голосовое ложилось на компьютер файлом
// без расширения, и проводник не мог его открыть.
private fun extensionFor(mime: String): String = com.point.core.flow.extensionForMime(mime)

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

class Inbox(private val dir: File, private val pdf: PdfText = PdfBoxText()) {

    private val classifier = ObjectClassifier()

    private companion object { const val BYTES_AT_A_TIME = 64 * 1024 }

    fun receive(name: String, mime: String, meta: Map<String, String>, source: InputStream): InboxItem {
        dir.mkdirs()
        val wanted = fileNameFor(name, mime)
        val safe = uniqueChildName(dir.list()?.toSet() ?: emptySet(), wanted)
        val target = File(dir, safe)
        val part = File(dir, "$safe.part")
        part.outputStream().use { out -> source.copyTo(out) }

        // Одна вещь — один узел (#1027). Защита от повтора была только у письма: вторая
        // отправка — новое письмо с новым id, и в папке появлялись `card2.jpg` и
        // `card2 (2).jpg` с одинаковым размером и дословно одинаковым знанием. Хуже всего
        // это при неосознанном повторе: отклик пропущен, человек жмёт ещё раз, копии растут.
        //
        // «То же самое» — это то же имя, тот же вид и те же байты. Совпадения одних байтов
        // мало: два разных объекта человека могут оказаться побайтно равными, и сливать их в
        // один узел значило бы решать за него.
        val twin = File(dir, wanted).takeIf {
            it.isFile && it.name != part.name && it.length() == part.length() && sameBytes(it, part)
        }
        if (twin != null) {
            part.delete()
            return wrap(twin, mime, meta + ("name" to name))
        }
        part.renameTo(target)
        return wrap(target, mime, meta + ("name" to name))
    }

    private fun sameBytes(one: File, other: File): Boolean = runCatching {
        one.inputStream().buffered().use { a ->
            other.inputStream().buffered().use { b ->
                val left = ByteArray(BYTES_AT_A_TIME)
                val right = ByteArray(BYTES_AT_A_TIME)
                while (true) {
                    val read = a.readNBytes(left, 0, left.size)
                    if (read != b.readNBytes(right, 0, right.size)) return false
                    if (read == 0) return true
                    for (i in 0 until read) if (left[i] != right[i]) return false
                }
            }
        }
        true
    }.getOrDefault(false)

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

    fun wipe() {
        dir.listFiles()?.forEach { runCatching { it.deleteRecursively() } }
    }

    private fun wrap(file: File, mime: String, meta: Map<String, String>): InboxItem {
        // Ноль-сигналы: голова файла спрашивается всегда, иначе файл без расширения —
        // мёртвый UNKNOWN (прецедент P1, повторён на ПК — аудит 2026-08-09, блок 1.5).
        val head = runCatching {
            file.inputStream().use { it.readNBytes(512) }
        }.getOrDefault(ByteArray(0))
        val classified = classifier.classify(mime, file.length(), file.name, head)

        // Скан узнаётся при приёме (#631): своего цикла обогащения у компьютера нет, а знать
        // про текстовый слой нужно раньше, чем человек увидит двери, — иначе «Извлечь текст»
        // на снимках страниц заканчивается пустотой вместо честного отсутствия двери.
        val state = if (classified.kind == ObjectKind.PDF && looksScanned(pdf, file)) {
            classified.with(Feature.IS_IMAGE_PDF)
        } else {
            classified
        }

        // Годность — часть состояния объекта (#684): та же пустота, что и на телефоне,
        // называет себя здесь же, а не только в Feature без объяснения человеку.
        val withFitness = if (state.has(Feature.UNUSABLE)) {
            meta + (META_UNUSABLE_REASON to EMPTY_FILE_REASON)
        } else {
            meta
        }

        // Прочитанное на телефоне приезжает значением и здесь снова становится знанием
        // (#811): текст ложится файлом рядом с объектом, а объект получает признак «текст
        // есть». Иначе компьютер предлагал распознать заново то, что уже прочитано.
        val arrivedText = withFitness[META_READ_TEXT]?.takeIf { it.isNotBlank() }
        val landed = if (arrivedText == null) {
            withFitness to state
        } else {
            val sidecar = File(file.parentFile, file.nameWithoutExtension + ".read.txt")
            val kept = runCatching { sidecar.writeText(arrivedText); sidecar.absolutePath }.getOrNull()
            val meta2 = if (kept == null) {
                withFitness - META_READ_TEXT
            } else {
                withFitness - META_READ_TEXT + (META_OCR_TEXT_REF to kept)
            }
            meta2 to state.with(Feature.HAS_TEXT)
        }

        return InboxItem(
            PointObject(
                id = UUID.randomUUID().toString(),
                mime = mime,
                uri = ScratchRef(file.absolutePath),
                state = landed.second,
                metadata = landed.first,
            ),
        )
    }
}
