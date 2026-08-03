package com.point.source

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.io.File
import javax.inject.Inject

/**
 * «Принять файл» — источник объекта из чужих рук (#388, продолжение Drop).
 *
 * Остальные источники добывают объект из самого устройства (камера, буфер, диктофон); этот —
 * из чужих рук: человек показывает ссылку или код, другой человек отправляет файл браузером, файл
 * приезжает и становится объектом. Дверь та же самая, что у остальных источников, поэтому в Point
 * не появляется ни нового экрана-«входящих», ни своей навигации: родился объект — дальше обычная
 * работа.
 */
class ReceiveFileSource @Inject constructor() : ObjectSource {

    override val id = "receive"
    override val label = "Принять файл"

    /** Нужна только сеть, и её проверяет сам приём: отказ он называет словами на своём экране. */
    override fun isAvailable(context: Context) = true

    override suspend fun request(context: Context): Intent =
        Intent(context, ReceiveActivity::class.java)

    override suspend fun read(context: Context, data: Intent?): Produced? = receivedToProduced(
        path = data?.getStringExtra(ReceiveActivity.EXTRA_PATH),
        mime = data?.getStringExtra(ReceiveActivity.EXTRA_MIME),
        exists = { path -> File(path).let { it.isFile && it.length() > 0 } },
        toUri = { path -> Uri.fromFile(File(path)).toString() },
    )
}

/**
 * Что родится из приёма.
 *
 * Пустой файл объектом не становится — как и у камеры: пустая карточка вместо файла хуже честной
 * тишины. Отмена ожидания тоже приходит сюда (без пути) и молчит: человек сам только что нажал
 * «Отмена», рассказывать ему об этом нечего.
 */
fun receivedToProduced(
    path: String?,
    mime: String?,
    exists: (String) -> Boolean,
    toUri: (String) -> String,
): Produced? {
    if (path.isNullOrBlank() || !exists(path)) return null
    return Produced(toUri(path), mime?.takeIf { it.isNotBlank() } ?: "application/octet-stream")
}
