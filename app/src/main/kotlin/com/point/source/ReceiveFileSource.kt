package com.point.source

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.point.core.flow.DropInbox
import com.point.core.flow.DropInboxBox
import java.io.File
import javax.inject.Inject

class ReceiveFileSource @Inject constructor(
    private val inbox: DropInbox,
) : ObjectSource {

    override val id = "receive"
    override val label = "Принять файл по ссылке"
    override val what = "дайте ссылку тому, кто пришлёт вам файл"
    override val account = true
    override val icon = "link"
    override val network = true

    override fun isAvailable(context: Context) = true

    override suspend fun request(context: Context): Intent =
        Intent(context, ReceiveActivity::class.java)

    override suspend fun read(context: Context, data: Intent?): Produced? {
        val produced = receivedToProduced(
            path = data?.getStringExtra(ReceiveActivity.EXTRA_PATH),
            mime = data?.getStringExtra(ReceiveActivity.EXTRA_MIME),
            exists = { path -> File(path).let { it.isFile && it.length() > 0 } },
            toUri = { path -> Uri.fromFile(File(path)).toString() },
        ) ?: return null

        // Объект есть — только теперь файл можно стирать на сервере. Раньше подтверждение
        // уходило сразу после скачивания, и любой сбой на этом участке терял файл насовсем:
        // на сервере его уже нет, у нас ещё нет, а прислал его чужой человек (живой прогон
        // 2026-08-10: ящик опустел, объект не появился).
        val box = data?.getStringExtra(ReceiveActivity.EXTRA_BOX)
        val fileId = data?.getStringExtra(ReceiveActivity.EXTRA_FILE_ID)
        if (!box.isNullOrBlank() && !fileId.isNullOrBlank()) {
            val opened = DropInboxBox(box, "")
            runCatching { inbox.ack(opened, fileId) }

            // Файл дошёл и подтверждён — дверь больше не нужна (#729). Прежде её убирала
            // только суточная уборка, и предел в пять ссылок выбирался обычным приёмом.
            runCatching { inbox.close(opened) }
        }
        return produced
    }
}

fun receivedToProduced(
    path: String?,
    mime: String?,
    exists: (String) -> Boolean,
    toUri: (String) -> String,
): Produced? {
    if (path.isNullOrBlank() || !exists(path)) return null
    return Produced(toUri(path), mime?.takeIf { it.isNotBlank() } ?: "application/octet-stream")
}
