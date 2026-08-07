package com.point.source

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.io.File
import javax.inject.Inject

class ReceiveFileSource @Inject constructor() : ObjectSource {

    override val id = "receive"
    override val label = "Принять файл"
    override val icon = "link"

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

fun receivedToProduced(
    path: String?,
    mime: String?,
    exists: (String) -> Boolean,
    toUri: (String) -> String,
): Produced? {
    if (path.isNullOrBlank() || !exists(path)) return null
    return Produced(toUri(path), mime?.takeIf { it.isNotBlank() } ?: "application/octet-stream")
}
