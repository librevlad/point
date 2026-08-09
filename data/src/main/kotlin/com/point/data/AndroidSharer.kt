package com.point.data

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.point.core.flow.Sharer
import com.point.core.model.PointObject
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject

class AndroidSharer @Inject constructor(
    @ApplicationContext private val context: Context,
) : Sharer {

    // Наружу файл уходит под именем объекта, а не scratch-идентификатором:
    // адресат «Переслать квитанцию» получал «4f94c663-….bin» (живой прогон 2026-08-09).
    private fun outboundUri(obj: PointObject): android.net.Uri {
        val authority = "${context.packageName}.fileprovider"
        val file = File(obj.uri.value)
        val name = obj.metadata["name"]?.takeIf { it.isNotBlank() }
        return if (name != null) {
            FileProvider.getUriForFile(context, authority, file, name)
        } else {
            FileProvider.getUriForFile(context, authority, file)
        }
    }

    override suspend fun share(obj: PointObject) {
        val uri = outboundUri(obj)

        val send = Intent(Intent.ACTION_SEND).apply {
            type = obj.mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, "Поделиться").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }

    override suspend fun shareAll(objs: List<PointObject>) {
        val uris = ArrayList(objs.map { outboundUri(it) })

        val send = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "*/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, "Поделиться").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }
}
