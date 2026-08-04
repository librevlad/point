package com.point.source

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import java.io.File
import javax.inject.Inject

/**
 * Звукозапись как источник объекта (#246) — **пишет сам Point**.
 *
 * Раньше запись отдавали чужому диктофону системным намерением. Владелец 04.08.2026 сказал, что
 * источника нет в списке вовсе, — и это оказалось правдой устройства, а не ошибкой экрана: на его
 * Samsung A34 на намерение «записать звук» не отвечает **ни одно** приложение. Источник честно
 * прятался по `isAvailable`, и возможности не существовало ровно там, где она нужнее всего.
 *
 * Записанное приходит объектом вида `AUDIO` (#223) — его можно сохранить, переслать и
 * **расшифровать**: получить дословный текст с короткой сутью сверху.
 *
 * Цена названа прямо: теперь Point просит доступ к микрофону, раньше не просил ничего. Просьба
 * приходит по тапу — то есть тогда, когда человек сам выбрал этот источник.
 */
class VoiceSource @Inject constructor() : ObjectSource {

    override val id = "voice"
    override val label = "Звукозапись"
    override val icon = "transcribe"

    /** Микрофон есть у любого телефона; отказ доступа называет словами сам экран записи. */
    override fun isAvailable(context: Context) = true

    override val permissions = listOf(Manifest.permission.RECORD_AUDIO)

    override suspend fun request(context: Context): Intent =
        Intent(context, RecordAudioActivity::class.java)

    override suspend fun read(context: Context, data: Intent?): Produced? = recordedToProduced(
        path = data?.getStringExtra(RecordAudioActivity.EXTRA_PATH),
        mime = data?.getStringExtra(RecordAudioActivity.EXTRA_MIME),
        exists = { path -> File(path).let { it.isFile && it.length() > 0 } },
        toUri = { path -> Uri.fromFile(File(path)).toString() },
    )
}

/**
 * Что родится из записи.
 *
 * Пустой файл объектом не становится — как у камеры и приёма: пустая карточка вместо звука хуже
 * честной тишины. Отмена приходит сюда же без пути и молчит: человек сам только что её нажал.
 */
fun recordedToProduced(
    path: String?,
    mime: String?,
    exists: (String) -> Boolean,
    toUri: (String) -> String,
): Produced? {
    if (path.isNullOrBlank() || !exists(path)) return null
    return Produced(toUri(path), mime?.takeIf { it.isNotBlank() } ?: RecordAudioActivity.MIME)
}
