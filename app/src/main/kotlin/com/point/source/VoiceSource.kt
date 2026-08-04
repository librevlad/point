package com.point.source

import android.content.Context
import android.content.Intent
import android.provider.MediaStore
import javax.inject.Inject

/**
 * Голос как источник объекта (#246) — системным диктофоном.
 *
 * Записывает чужое приложение, поэтому `RECORD_AUDIO` Point не просит. Записанное приходит
 * объектом вида `AUDIO` (#223) — его можно сохранить, переслать и **расшифровать**: получить
 * дословный текст с короткой сутью сверху.
 */
class VoiceSource @Inject constructor() : ObjectSource {

    override val id = "voice"
    override val label = "Голос"
    override val icon = "transcribe"

    override fun isAvailable(context: Context): Boolean =
        Intent(MediaStore.Audio.Media.RECORD_SOUND_ACTION)
            .resolveActivity(context.packageManager) != null

    override suspend fun request(context: Context): Intent =
        Intent(MediaStore.Audio.Media.RECORD_SOUND_ACTION)

    override suspend fun read(context: Context, data: Intent?): Produced? {
        val uri = data?.data ?: return null // человек вышел из диктофона, не записав
        val mime = context.contentResolver.getType(uri) ?: "audio/*"
        return Produced(uri.toString(), mime)
    }
}
