package com.point.data

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.point.core.flow.Sharer
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject

class AndroidSharer @Inject constructor(
    @ApplicationContext private val context: Context,
) : Sharer {

    // Наружу файл уходит под именем объекта, а не scratch-идентификатором:
    // адресат «Переслать квитанцию» получал «4f94c663-….bin» (живой прогон 2026-08-09).
    //
    // Имя есть не всегда: часть провайдеров не отдаёт `DISPLAY_NAME`, и голосовое из
    // мессенджера приходит безымянным. Тип при этом известен всегда — значит и расширение
    // известно (#867). Раньше в этом случае наружу уходил голый идентификатор, и адресат
    // снова видел «.bin».
    private fun outboundUri(obj: PointObject): android.net.Uri {
        val authority = "${context.packageName}.fileprovider"
        val file = File(obj.uri.value)
        val name = com.point.core.flow.outboundFileName(obj.metadata["name"], obj.mime)

        // Подсказки displayName системному листу мало (#1111): часть приёмников и сам лист
        // читают последний сегмент пути — и показывали scratch-идентификатор. Файл уходит
        // копией под своим именем; копия живёт в scratch и убирается вместе с ним.
        val named = runCatching {
            File(file.parentFile, "share").apply { mkdirs() }
                .let { dir -> File(dir, name) }
                .also { if (!it.isFile || it.length() != file.length()) file.copyTo(it, overwrite = true) }
        }.getOrNull()
        return FileProvider.getUriForFile(context, authority, named ?: file, name)
    }

    override suspend fun share(obj: PointObject) {
        val text = shareableTextOf(obj)
        val send = if (text != null) {
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
            }
        } else {
            Intent(Intent.ACTION_SEND).apply {
                type = obj.mime
                putExtra(Intent.EXTRA_STREAM, outboundUri(obj))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
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

/**
 * Текстом делятся текстом, а не файлом (владелец 11.08.2026: «текст принимается, отправить
 * не даёт»).
 *
 * Наружу всегда уходил файл через FileProvider. Для сообщения это значит вложение «.txt»
 * вместо самого сообщения — половина приложений его просто не берёт. А у найденного
 * значения — телефона, даты, номера — файла нет вовсе: там в ссылке лежит само значение,
 * FileProvider на нём срывается, и отправка обрывается молча.
 *
 * Длинный документ остаётся файлом: в сообщение такого размера его всё равно не положить.
 */
internal fun shareableTextOf(obj: PointObject): String? = when {
    obj.uri !is ScratchRef -> obj.uri.value.takeIf { it.isNotBlank() }

    obj.mime.startsWith("text/") -> runCatching { File(obj.uri.value).readText() }
        .getOrNull()
        ?.takeIf { it.isNotBlank() && it.length <= MAX_TEXT_IN_MESSAGE }

    else -> null
}

/** Длиннее — уже документ, а не сообщение: такой уходит файлом. */
private const val MAX_TEXT_IN_MESSAGE = 100_000
