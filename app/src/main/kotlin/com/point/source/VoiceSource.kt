package com.point.source

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.point.core.flow.stampedObjectName
import java.io.File
import javax.inject.Inject

class VoiceSource @Inject constructor() : ObjectSource {

    override val id = "voice"
    override val label = "Звукозапись"

    override val what = "записать голос и расшифровать словами"
    override val icon = "transcribe"

    override fun isAvailable(context: Context) = true

    override val permissions = listOf(Manifest.permission.RECORD_AUDIO)

    override suspend fun request(context: Context): Intent =
        Intent(context, RecordAudioActivity::class.java)

    override suspend fun read(context: Context, data: Intent?): Produced? = recordedToProduced(
        path = data?.getStringExtra(RecordAudioActivity.EXTRA_PATH),
        mime = data?.getStringExtra(RecordAudioActivity.EXTRA_MIME),
        exists = { path -> File(path).let { it.isFile && it.length() > 0 } },
        toUri = { path -> Uri.fromFile(File(path)).toString() },

        recordedAt = { path -> File(path).lastModified().takeIf { it > 0 } ?: System.currentTimeMillis() },
    )
}

fun recordedToProduced(
    path: String?,
    mime: String?,
    exists: (String) -> Boolean,
    toUri: (String) -> String,
    recordedAt: (String) -> Long = { System.currentTimeMillis() },
): Produced? {
    if (path.isNullOrBlank() || !exists(path)) return null
    return Produced(
        uri = toUri(path),
        mime = mime?.takeIf { it.isNotBlank() } ?: RecordAudioActivity.MIME,
        name = stampedObjectName("Запись", recordedAt(path)),
    )
}
