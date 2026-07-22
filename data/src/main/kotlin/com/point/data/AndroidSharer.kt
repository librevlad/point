package com.point.data

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.point.core.flow.Sharer
import com.point.core.model.PointObject
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject

/**
 * Launches the system share sheet from the application context (no Activity).
 * The scratch file is exposed to other apps through a [FileProvider] content Uri
 * (sharing a raw file:// Uri would throw FileUriExposedException).
 */
class AndroidSharer @Inject constructor(
    @ApplicationContext private val context: Context,
) : Sharer {

    override suspend fun share(obj: PointObject) {
        val authority = "${context.packageName}.fileprovider"
        val uri = FileProvider.getUriForFile(context, authority, File(obj.uri.value))

        val send = Intent(Intent.ACTION_SEND).apply {
            type = obj.mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, "Поделиться").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) // required to start from app context
        }
        context.startActivity(chooser)
    }

    override suspend fun shareAll(objs: List<PointObject>) {
        val authority = "${context.packageName}.fileprovider"
        val uris = ArrayList(objs.map { FileProvider.getUriForFile(context, authority, File(it.uri.value)) })

        val send = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "*/*" // mixed types — the receiver picks what it can take
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, "Поделиться").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }
}
