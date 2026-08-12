package com.point.source

import android.content.Context
import android.content.Intent
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import javax.inject.Inject

class CameraSource @Inject constructor() : ObjectSource {

    override val id = "camera"
    override val label = "Снять камерой"

    override val what = "документ или вещь прямо сейчас"
    override val icon = "camera"

    private var target: File? = null

    override fun saveState(): String? = target?.absolutePath

    override fun restoreState(state: String?) {
        target = state?.takeIf { it.isNotBlank() }?.let(::File)
    }

    override fun isAvailable(context: Context): Boolean =
        Intent(MediaStore.ACTION_IMAGE_CAPTURE).resolveActivity(context.packageManager) != null

    override suspend fun request(context: Context): Intent {
        val dir = File(context.cacheDir, "capture").apply { mkdirs() }
        val file = File(dir, "shot-${System.currentTimeMillis()}.jpg")
        target = file
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        return Intent(MediaStore.ACTION_IMAGE_CAPTURE)
            .putExtra(MediaStore.EXTRA_OUTPUT, uri)
            .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    }

    override suspend fun read(context: Context, data: Intent?): Produced? {
        val file = target ?: return null
        target = null

        val takenAt = file.lastModified().takeIf { it > 0 } ?: System.currentTimeMillis()
        return captureToProduced(android.net.Uri.fromFile(file).toString(), file.length(), takenAt)
    }
}
