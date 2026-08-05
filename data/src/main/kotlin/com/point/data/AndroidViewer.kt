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

/**
 * Opens a scratch object in an external app via ACTION_VIEW from the application
 * context (no Activity). The file is handed over as a [FileProvider] content Uri
 * with a read grant — the same authority Share uses. Mirrors [AndroidSharer], but
 * VIEW instead of SEND. If no app can handle the MIME, throws with a clean
 * message so the realizer surfaces a recoverable Failure.
 */
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
        /**
         * Тип файла человеку не адрес, а загадка (#541).
         *
         * Прежняя строка вставляла сырой тип из системы: «Нет приложения, которое открывает
         * «application/vnd.openxmlformats-officedocument.wordprocessingml.document»». Это не
         * ответ ни на «что случилось» — на экране обрывок машинного словаря, — ни тем более на
         * «что делать». Тип сюда приходит от системы, и назвать его человеческим словом честно
         * мы не можем: догадка про «документ Word» на чужом `vnd.…` была бы выдумкой.
         *
         * Поэтому сказано то, что известно наверняка: открыть **нечем**, и выход — сохранить
         * файл и открыть его оттуда. Случай «не с этим объектом»: сам Point здесь ни при чём.
         */
        const val NO_APP_FOR_IT =
            "На телефоне нет приложения, которое открывает такие файлы — сохраните файл и откройте его оттуда"
    }
}
