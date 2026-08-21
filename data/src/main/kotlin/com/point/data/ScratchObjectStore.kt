package com.point.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import android.webkit.MimeTypeMap
import com.point.core.flow.CollectionContent
import com.point.core.flow.EMPTY_FILE_REASON
import com.point.core.flow.META_SIZE
import com.point.core.flow.META_UNUSABLE_REASON
import com.point.core.flow.ObjectClassifier
import com.point.core.flow.ObjectStore
import com.point.core.flow.collectionContent
import com.point.core.model.PointObject
import com.point.core.model.ResultObject
import com.point.core.model.ScratchRef
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject

class ScratchObjectStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val classifier: ObjectClassifier,

    // Каталог копий называется одним местом (#1026): его же стирает «Забыть всё», и
    // разъехаться этим двоим нельзя.
    @com.point.data.di.ScratchDir private val place: File,
) : ObjectStore {

    private val scratchDir: File
        get() = place.apply { mkdirs() }

    override suspend fun nameOf(sourceUri: String): String? = withContext(Dispatchers.IO) {
        runCatching { displayName(Uri.parse(sourceUri)) }.getOrNull()
    }

    override suspend fun ingest(sourceUri: String, mime: String): PointObject =
        withContext(Dispatchers.IO) {

            logFailure("ingest failed (mime=$mime)") {
                val uri = Uri.parse(sourceUri)
                val id = UUID.randomUUID().toString()
                val dest = File(scratchDir, id)

                val size = context.contentResolver.openInputStream(uri)?.use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                } ?: error("Не удалось открыть источник: $sourceUri")

                // Имя показывается человеку и уезжает дальше — в «Сохранить», в имя копии
                // на компьютере, в экспорт. Чистится один раз здесь, у входа (#865).
                val name = displayName(uri)?.let { com.point.core.flow.safeFileName(it) }
                PointObject(
                    id = id,
                    mime = mime,
                    uri = ScratchRef(dest.absolutePath),
                    state = classifier.classify(mime, size, name, headOf(dest)),

                    metadata = buildMap {
                        name?.let { put("name", it) }
                        put(META_SIZE, size.toString())
                        putAll(emptyFileFact(size))
                    },
                )
            }
        }

    override suspend fun ingestMultiple(sources: List<String>): PointObject =
        withContext(Dispatchers.IO) {
            logFailure("ingest failed (files=${sources.size})") {
                val id = UUID.randomUUID().toString()
                val dir = File(scratchDir, id).apply { mkdirs() }
                sources.forEachIndexed { index, source ->
                    val uri = Uri.parse(source)
                    // Имя даёт чужое приложение — в путь оно не годится (#865).
                    val name = com.point.core.flow.safeFileName(
                        displayName(uri).orEmpty(),
                        ifBlank = "file-${index + 1}",
                    )
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        uniqueFile(dir, name).outputStream().use { output -> input.copyTo(output) }
                    }
                }
                PointObject(
                    id = id,
                    mime = "inode/directory",
                    uri = ScratchRef(dir.absolutePath),
                    state = classifier.classify("inode/directory", 0),
                    metadata = mapOf("name" to "Набор (${sources.size})"),
                )
            }
        }

    override suspend fun put(
        result: ResultObject,
        from: PointObject?,
        by: com.point.core.model.CapabilityId?,
    ): PointObject =
        withContext(Dispatchers.IO) {

            val size = File(result.uri.value).length()
            PointObject(
                id = UUID.randomUUID().toString(),
                mime = result.mime,
                uri = result.uri,
                state = classifier.classify(result.mime, size),

                metadata = result.metadata + (META_SIZE to size.toString()) + emptyFileFact(size),

                // Откуда взялся и кто сделал — часть самого объекта, а не только кадра на
                // экране (#1127): по графу должно быть видно происхождение, даже если
                // человек ушёл из разбора и вернулся в него из «Недавнего».
                provenance = result.provenance,
                sourceObjects = listOfNotNull(from?.id),
                creatorAction = by?.value,
            )
        }

    override suspend fun children(collection: PointObject, limit: Int): CollectionContent<PointObject> =
        withContext(Dispatchers.IO) {
            val root = File(collection.uri.value)
            if (!root.isDirectory) return@withContext CollectionContent.empty()
            collectionContent(
                entries = root.walkTopDown(),
                limit = limit,
                isFile = { it.isFile },
                name = { it.name },

                // Порядок страниц — знание самого набора (#1207): человек переставил их на
                // экране, и список идёт так, как он велел; остальное — по имени.
                order = com.point.core.flow.collectionOrder(collection.metadata),
            ).map { file ->
                val mime = mimeOf(file.name)

                val size = file.length()
                PointObject(
                    id = UUID.randomUUID().toString(),
                    mime = mime,
                    uri = ScratchRef(file.absolutePath),
                    state = classifier.classify(mime, size, file.name, headOf(file)),
                    metadata = mapOf("name" to file.name, META_SIZE to size.toString()) + emptyFileFact(size),

                    // Вещь из коллекции знает свою коллекцию (#1132): страница разложенного
                    // PDF и файл из архива иначе жили в Graph без источника — открыть их
                    // можно, а вернуться к документу или объяснить, откуда они, нельзя.
                    provenance = collection.provenance,
                    sourceObjects = listOf(collection.id),
                    creatorAction = collection.creatorAction,
                )
            }
        }

    private fun mimeOf(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    /**
     * Годность — часть состояния объекта (#684): нулевой размер уже классификатор отметил
     * как `Feature.UNUSABLE` (`ObjectClassifier`) — здесь та же проверка кладёт причину
     * рядом, человеческими словами, чтобы знание не разошлось с фактом.
     */
    private fun emptyFileFact(size: Long): Map<String, String> =
        if (size == 0L) mapOf(META_UNUSABLE_REASON to EMPTY_FILE_REASON) else emptyMap()

    // Первые байты объекта для классификации: имя и mime могут молчать, байты — нет.
    private fun headOf(file: File): ByteArray = runCatching {
        file.inputStream().use { it.readNBytes(512) }
    }.getOrDefault(ByteArray(0))

    private fun uniqueFile(dir: File, name: String): File {
        var candidate = File(dir, name)
        if (!candidate.exists()) return candidate
        val base = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "")
        var i = 1
        do {
            candidate = File(dir, if (ext.isBlank()) "$base-$i" else "$base-$i.$ext")
            i++
        } while (candidate.exists())
        return candidate
    }

    override suspend fun readText(obj: PointObject, limit: Int): String =
        withContext(Dispatchers.IO) {
            val file = File(obj.uri.value)
            if (!file.isFile) return@withContext ""

            val out = StringBuilder()
            val buffer = CharArray(8192)
            file.bufferedReader().use { reader ->
                while (out.length < limit) {
                    val n = reader.read(buffer)
                    if (n < 0) break
                    out.append(buffer, 0, minOf(n, limit - out.length))
                }
            }
            out.toString()
        }

    override suspend fun newScratchFile(extension: String): ScratchRef =
        withContext(Dispatchers.IO) {
            val ext = extension.trimStart('.')
            val name = if (ext.isBlank()) UUID.randomUUID().toString() else "${UUID.randomUUID()}.$ext"
            ScratchRef(File(scratchDir, name).absolutePath)
        }

    override suspend fun clear() {
        withContext(Dispatchers.IO) { scratchDir.deleteRecursively() }
    }

    /**
     * Брошенная копия убирается при запуске (#1012).
     *
     * Свежая остаётся: к ней человек и возвращается после смерти процесса. Старше срока —
     * значит, к ней уже не вернулись, и она лежит на диске без спроса.
     */
    override suspend fun forgetOlderThan(before: Long) {
        withContext(Dispatchers.IO) {
            runCatching {
                scratchDir.listFiles().orEmpty()
                    .filter { it.lastModified() < before }
                    .forEach { it.deleteRecursively() }
            }.onFailure { Log.w(TAG, "не вышло убрать брошенные копии", it) }
        }
    }

    /**
     * След остаётся у любого отказа, включая отмену (#939).
     *
     * Отмена не логировалась вовсе — «это же не ошибка». А оказалось, что единственный
     * отказ, который сейчас случается на приёме объекта, — ровно она: человек видел красный
     * экран, а в журнале устройства за всю серию провалов не было ни одной строки. Отмена
     * пишется тише обычного сбоя, но пишется.
     */
    private inline fun <T> logFailure(what: String, block: () -> T): T =
        runCatching(block)
            .onFailure {
                if (it is CancellationException) Log.i(TAG, "$what — отменено") else Log.w(TAG, what, it)
            }
            .getOrThrow()

    private fun displayName(uri: Uri): String? = runCatching {
        if (uri.scheme == "file") return uri.lastPathSegment
        context.contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()

    private companion object {
        const val TAG = "PointScratch"
    }
}
