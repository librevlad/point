package com.point.data

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.point.core.flow.Viewer
import com.point.core.model.PointObject
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject

class AndroidViewer @Inject constructor(
    @ApplicationContext private val context: Context,
) : Viewer {

    override suspend fun view(obj: PointObject) {
        val authority = "${context.packageName}.fileprovider"
        val uri = FileProvider.getUriForFile(context, authority, File(obj.uri.value))

        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, obj.mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(view)
        } catch (e: ActivityNotFoundException) {
            error(NO_APP_FOR_IT)
        }
    }

    private companion object {

        const val NO_APP_FOR_IT =
            "На телефоне нет приложения, которое открывает такие файлы — сохраните файл и откройте его оттуда"
    }
}
