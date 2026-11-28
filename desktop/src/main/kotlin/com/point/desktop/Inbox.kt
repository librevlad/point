package com.point.desktop

import com.point.core.flow.EMPTY_FILE_REASON
import com.point.core.flow.META_UNUSABLE_REASON
import com.point.core.flow.ObjectClassifier
import com.point.core.flow.knowingAddress
import com.point.core.flow.readerFailure
import com.point.core.model.Feature
import com.point.core.model.ObjectKind
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import com.point.core.model.tr
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
    val base = if (flat.isBlank()) tr("объект") else flat
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
        val safe = uniqueChildName(dir.list()?.toSet() ?: emptySet(), tr("Заметка.txt"))
        val target = File(dir, safe)
        target.writeText(text)
        return wrap(target, "text/plain", mapOf("name" to safe))
    }

    fun addFile(path: String): InboxItem {
        val f = File(path)
        return wrap(f, mimeFor(f.name), mapOf("name" to f.name))
    }

    /**
     * Пачка файлов — один объект-коллекция с детьми, как на телефоне (#1099, решение
     * владельца: одна модель Graph важнее удобства одной поверхности). Дети живут путями в
     * манифесте; вход раскрывает их, действия над пачкой работают одинаково.
     */
    fun addFiles(paths: List<String>): InboxItem {
        require(paths.size > 1) { "набор — это больше одного файла" }
        return collection(paths)
    }

    /**
     * Сделанное действием ложится объектом (#1407): файл — собой, набор частей — тем же
     * объектом-коллекцией с манифестом, что и пачка брошенных файлов (#1099).
     *
     * «Страницы» и «Слайды» — общие с телефоном действия, и результат у них набор: папка, в
     * которой лежат части. Компьютер принимал результат только файлом, папку молча
     * выбрасывал — и человек получал «Готово» без единой страницы. Второго вида набора здесь
     * не заводится: части встают в манифест в порядке набора (`collection.order`, #1207), и
     * этот же порядок остаётся знанием набора — его читают «Объединить в PDF» и «Сканировать
     * в PDF».
     *
     * Набор из одной части — всё равно набор: действие обещало набор (`produces`), и
     * однострочный PDF разобран на страницы так же честно, как толстый. Пустая папка
     * объектом не становится — входить в ней не во что.
     */
    fun land(made: com.point.core.model.ResultObject): InboxItem? {
        val where = File(made.uri.value)
        return when {
            where.isFile -> addFile(where.absolutePath)
            where.isDirectory -> partsOf(where, made.metadata)
            else -> null
        }
    }

    private fun partsOf(set: File, known: Map<String, String>): InboxItem? {
        val parts = set.walkTopDown().filter(File::isFile).toList()
        if (parts.isEmpty()) return null
        val order = com.point.core.flow.collectionOrder(known)
        val ordered = com.point.core.flow.inCollectionOrder(parts, order) { it.relativeTo(set).invariantSeparatorsPath }
        return collection(
            ordered.map(File::getAbsolutePath),
            name = known["name"]?.takeIf { it.isNotBlank() },
            order = known[com.point.core.flow.META_COLLECTION_ORDER],
        )
    }

    /** Один вид набора на всё, что им становится (#1099): манифест путей и объект-коллекция. */
    private fun collection(paths: List<String>, name: String? = null, order: String? = null): InboxItem {
        dir.mkdirs()
        val safe = uniqueChildName(dir.list()?.toSet() ?: emptySet(), tr("Набор.list"))
        val manifest = File(dir, safe)
        manifest.writeText(paths.joinToString(separator = System.lineSeparator()))
        return InboxItem(
            com.point.core.model.PointObject(
                id = java.util.UUID.randomUUID().toString(),
                mime = COLLECTION_MIME,
                uri = com.point.core.model.ScratchRef(manifest.absolutePath),
                state = com.point.core.model.ObjectState(com.point.core.model.ObjectKind.COLLECTION),
                metadata = listOfNotNull(
                    "name" to (name ?: (tr("Набор · ") + paths.size)),
                    "collection.size" to paths.size.toString(),
                    order?.let { com.point.core.flow.META_COLLECTION_ORDER to it },
                ).toMap(),
            ),
        )
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

    /** Сколько места освободила уборка — человеку говорят числом, а не молчанием (#1081). */
    fun wipe(): Long {
        var freed = 0L
        dir.listFiles()?.forEach { entry ->
            val weight = runCatching {
                entry.walkTopDown().filter(java.io.File::isFile).sumOf(java.io.File::length)
            }.getOrDefault(0L)
            if (runCatching { entry.deleteRecursively() }.getOrDefault(false)) freed += weight
        }
        return freed
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
        //
        // Тем же чтением узнаётся и обломок вместо документа: слой не достался — и только
        // тогда спрашивается, открывается ли файл как PDF вообще (`brokenPdf`). Пустой файл
        // уже назван пустым и второй причины не получает — как и на телефоне, уже отмеченный
        // объект не переотмечается.
        val readsAsPdf = classified.kind == ObjectKind.PDF && !classified.has(Feature.UNUSABLE)
        val layer = if (readsAsPdf) pdf.of(file) else null
        val broken = if (readsAsPdf && layer == null) brokenPdf(pdf, file) else null
        val state = when {
            broken != null -> classified.with(Feature.UNUSABLE)
            looksScanned(layer) -> classified.with(Feature.IS_IMAGE_PDF)
            else -> classified
        }

        // Годность — часть состояния объекта (#684): та же пустота, что и на телефоне,
        // называет себя здесь же, а не только в Feature без объяснения человеку. Повреждённый
        // PDF называет себя словами телефона — их произносит один словарь ридера.
        val withFitness = when {
            broken != null -> meta + (META_UNUSABLE_REASON to readerFailure(broken, ObjectKind.PDF))
            state.has(Feature.UNUSABLE) -> meta + (META_UNUSABLE_REASON to EMPTY_FILE_REASON)
            else -> meta
        }

        // Прочитанное на телефоне приезжает значением и здесь снова становится знанием
        // (#811): текст ложится файлом рядом с объектом, а объект получает признак «текст
        // есть». Иначе компьютер предлагал распознать заново то, что уже прочитано.
        // Место знания у документа одно на всех, кто его кладёт (#995): своё имя здесь
        // рождало второе место, а имя без расширения — общее место у «смета.xlsx» и
        // «смета.pdf», где текст одного затирал текст другого.
        val arrivedText = com.point.core.flow.textArrivedFromTravel(withFitness)
        val kept = arrivedText?.let { keepTextBesideDocument(file, it)?.absolutePath }
        val landed = com.point.core.flow.knowledgeArrivedFromTravel(withFitness, kept)

        // Приём с телефона — такая же дверь рождения объекта из файла, как приём на самом
        // телефоне (#999): ссылка приходит сюда со своим адресом, а не одними байтами,
        // которые каждое действие разбирало бы само.
        return InboxItem(
            PointObject(
                id = UUID.randomUUID().toString(),
                mime = mime,
                uri = ScratchRef(file.absolutePath),
                state = landed.features.fold(state) { acc, feature -> acc.with(feature) },
                metadata = landed.metadata,
            ).knowingAddress(),
        )
    }
}

const val COLLECTION_MIME = "application/x-point-collection"

/** Дети набора — пути из манифеста; читаются на месте, реестр не заводится (#1099). */
fun collectionChildren(obj: com.point.core.model.PointObject): List<String> =
    if (obj.state.kind != com.point.core.model.ObjectKind.COLLECTION) emptyList()
    else runCatching {
        File(obj.uri.value).readLines().map(String::trim).filter { it.isNotBlank() }
    }.getOrDefault(emptyList())
