package com.point.source

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import javax.inject.Inject

/**
 * Буфер обмена как источник объекта (#246).
 *
 * Чужой активити не нужно: буфер читается прямо здесь, потому что экран выбора уже на переднем
 * плане, — единственное состояние, в котором Android отдаёт содержимое буфера.
 */
class ClipboardSource @Inject constructor() : ObjectSource {

    override val id = "clipboard"
    override val label = "Буфер обмена"
    override val icon = "copy"

    override fun isAvailable(context: Context) =
        context.getSystemService(Context.CLIPBOARD_SERVICE) is ClipboardManager

    override suspend fun request(context: Context): Intent? = null

    override suspend fun read(context: Context, data: Intent?): Produced? {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val item = cm?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)
        val uri = item?.uri
        val produced = clipToProduced(
            text = item?.text?.toString(),
            uri = uri?.toString(),
            mime = uri?.let { context.contentResolver.getType(it) },
            // `cacheTextFile` — internal в пакете `com.point`, отсюда не виден; текст кладётся тем
            // же способом, но своей строкой: дублировать один вызов дешевле, чем расширять
            // видимость ради него.
            textFile = { text ->
                val file = java.io.File.createTempFile("clip-", ".txt", context.cacheDir)
                file.writeText(text)
                android.net.Uri.fromFile(file).toString()
            },
        )
        // Пустота названа словами: молчание в ответ на тап — та же ложь, что заглушка вместо
        // статуса (#358).
        if (produced == null) Toast.makeText(context, "В буфере пусто", Toast.LENGTH_SHORT).show()
        return produced
    }
}
