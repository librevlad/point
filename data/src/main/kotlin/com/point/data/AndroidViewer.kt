package com.point.data

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.FileProvider
import com.point.core.flow.Viewer
import com.point.core.model.PointObject
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject

class AndroidViewer @Inject constructor(
    @ApplicationContext private val context: Context,
) : Viewer {

    /**
     * «Открыть» — это другое приложение, никогда не сам Point (#1419).
     *
     * Point по манифесту принимает `VIEW` для документов и для любого типа файла, поэтому там,
     * где стороннего приложения нет, система запускала его же — открывать его же файл: приём стирал рабочую
     * папку, источник пропадал, и человек читал «Не удалось открыть объект» про целый файл.
     * Свои двери из выбора исключаются; никого, кроме себя, — честный отказ словами.
     */
    override suspend fun view(obj: PointObject) {
        val authority = "${context.packageName}.fileprovider"
        val uri = FileProvider.getUriForFile(context, authority, File(obj.uri.value))

        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, obj.mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val handlers = context.packageManager
            .queryIntentActivities(view, PackageManager.MATCH_DEFAULT_ONLY)
            .map { ComponentName(it.activityInfo.packageName, it.activityInfo.name) }
        val (ours, others) = handlers.partition { it.packageName == context.packageName }
        if (others.isEmpty()) error(NO_APP_FOR_IT)

        val target = when (others.size) {
            1 -> view.setPackage(others.single().packageName)
            else -> Intent.createChooser(view, null).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (ours.isNotEmpty()) putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, ours.toTypedArray())
            }
        }
        try {
            context.startActivity(target)
        } catch (e: ActivityNotFoundException) {
            error(NO_APP_FOR_IT)
        }
    }

    private companion object {

        const val NO_APP_FOR_IT =
            "На телефоне нет приложения, которое открывает такие файлы — сохраните файл и откройте его оттуда"
    }
}
