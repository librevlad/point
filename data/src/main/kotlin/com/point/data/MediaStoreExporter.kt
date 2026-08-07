package com.point.data

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.point.core.flow.Exporter
import com.point.core.model.PointObject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

class MediaStoreExporter @Inject constructor(
    @ApplicationContext private val context: Context,
) : Exporter {

    override suspend fun export(obj: PointObject): String = withContext(Dispatchers.IO) {
        val name = obj.metadata["name"]?.takeIf { it.isNotBlank() } ?: defaultName(obj.mime)
        val source = File(obj.uri.value)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, obj.mime)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val item = resolver.insert(collection, values) ?: error("Не удалось создать запись в Downloads")
            resolver.openOutputStream(item)?.use { out -> source.inputStream().use { it.copyTo(out) } }
                ?: error("Не удалось открыть поток для записи")
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(item, values, null, null)
            "Downloads/$name"
        } else {
            val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                ?: error("Внешнее хранилище недоступно")
            val dest = File(dir, name)
            source.inputStream().use { input -> dest.outputStream().use { input.copyTo(it) } }
            dest.absolutePath
        }
    }

    private fun defaultName(mime: String): String {
        val ext = when {
            mime.startsWith("image/") -> mime.substringAfter('/')
            mime == "application/pdf" -> "pdf"
            mime == "application/zip" -> "zip"
            mime.startsWith("text/") -> "txt"
            else -> "bin"
        }
        return "point-export.$ext"
    }
}
