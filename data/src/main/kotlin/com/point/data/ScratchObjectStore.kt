package com.point.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import com.point.core.flow.ObjectClassifier
import com.point.core.flow.ObjectStore
import com.point.core.model.PointObject
import com.point.core.model.ResultObject
import com.point.core.model.ScratchRef
import dagger.hilt.android.qualifiers.ApplicationContext
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

    override suspend fun children(collection: PointObject): List<PointObject> =
        withContext(Dispatchers.IO) {
            val root = File(collection.uri.value)
            if (!root.isDirectory) return@withContext emptyList()
            root.walkTopDown()
                .filter { it.isFile }
                .map { file ->
                    val mime = mimeOf(file.name)
                    PointObject(
                        id = UUID.randomUUID().toString(),
                        mime = mime,
                        uri = ScratchRef(file.absolutePath),
                        state = classifier.classify(mime, file.length(), file.name),
                        metadata = mapOf("name" to file.name),
                    )
                }
                .sortedBy { it.metadata["name"]?.lowercase() }
                .toList()
        }

    private fun mimeOf(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
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

    private fun displayName(uri: Uri): String? = runCatching {
        if (uri.scheme == "file") return uri.lastPathSegment
        context.contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()
}
