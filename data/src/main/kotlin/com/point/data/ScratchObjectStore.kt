package com.point.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import android.webkit.MimeTypeMap
import com.point.core.flow.CollectionContent
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

/**
 * Private scratch store backed by `filesDir/scratch`.
 *
 * On [ingest] it copies the shared bytes in immediately, so every later step
 * works on our own file — never on the original `content://` Uri whose read
 * grant dies with the receiving Activity. [clear] wipes the whole scratch dir.
 */
class ScratchObjectStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val classifier: ObjectClassifier,
) : ObjectStore {

    private val scratchDir: File
        get() = File(context.filesDir, "scratch").apply { mkdirs() }

    override suspend fun ingest(sourceUri: String, mime: String): PointObject =
        withContext(Dispatchers.IO) {
            // Экран отказа говорит человеку словами — «Не удалось открыть объект» (#358), без
            // хвоста исключения. Значит техническая причина обязана остаться здесь: она известна
            // только приёмнику, и без этой строки разбитый шаринг не оставляет вообще ни следа.
            logFailure("ingest failed (mime=$mime)") {
                val uri = Uri.parse(sourceUri)
                val id = UUID.randomUUID().toString()
                val dest = File(scratchDir, id)

                val size = context.contentResolver.openInputStream(uri)?.use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                } ?: error("Не удалось открыть источник: $sourceUri")

                val name = displayName(uri)
                PointObject(
                    id = id,
                    mime = mime,
                    uri = ScratchRef(dest.absolutePath),
                    state = classifier.classify(mime, size, name),
                    metadata = buildMap { name?.let { put("name", it) } },
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
                    val name = displayName(uri) ?: "file-${index + 1}"
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

    override suspend fun put(result: ResultObject): PointObject =
        withContext(Dispatchers.IO) {
            // The executor already wrote the result into scratch; just wrap it.
            val size = File(result.uri.value).length()
            PointObject(
                id = UUID.randomUUID().toString(),
                mime = result.mime,
                uri = result.uri,
                state = classifier.classify(result.mime, size),
                metadata = result.metadata,
            )
        }

    /**
     * Содержимое набора — с двумя пределами (#460).
     *
     * Обход считает файлы, но объекты строит только для показанной части: `classify` + `length()`
     * на каждый файл тысячефайлового архива — работа ради списка, из которого откроют один.
     * Сколько файлов на самом деле, знает [CollectionContent.total] — экран говорит это словами.
     */
    override suspend fun children(collection: PointObject, limit: Int): CollectionContent<PointObject> =
        withContext(Dispatchers.IO) {
            val root = File(collection.uri.value)
            if (!root.isDirectory) return@withContext CollectionContent.empty()
            collectionContent(
                entries = root.walkTopDown(),
                limit = limit,
                isFile = { it.isFile },
                name = { it.name },
            ).map { file ->
                val mime = mimeOf(file.name)
                PointObject(
                    id = UUID.randomUUID().toString(),
                    mime = mime,
                    uri = ScratchRef(file.absolutePath),
                    state = classifier.classify(mime, file.length(), file.name),
                    metadata = mapOf("name" to file.name),
                )
            }
        }

    private fun mimeOf(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    /** A non-colliding file in [dir] for [name] (appends -1, -2… on collision). */
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
            // Bounded read: never pull a huge file fully into memory for a preview.
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
     * Причина сбоя приёма — в лог, а сам сбой — дальше наверх: что сказать человеку, решает экран.
     *
     * Отмена сбоем не считается: человек передумал делиться, и «ingest failed» в логе увело бы
     * того, кто чинит, в несуществующую поломку.
     */
    private inline fun <T> logFailure(what: String, block: () -> T): T =
        runCatching(block)
            .onFailure { if (it !is CancellationException) Log.w(TAG, what, it) }
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
