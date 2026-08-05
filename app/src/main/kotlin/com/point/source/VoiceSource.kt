package com.point.source

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.point.core.flow.stampedObjectName
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
        // Когда запись закончилась — по самому файлу, а не по часам этого вызова: экран записи
        // мог быть выгружен и вернуться минутами позже (#454), а «Запись, 4 авг 19:25» обязана
        // называть время записи.
        recordedAt = { path -> File(path).lastModified().takeIf { it > 0 } ?: System.currentTimeMillis() },
    )
}

/**
 * Что родится из записи.
 *
 * Пустой файл объектом не становится — как у камеры и приёма: пустая карточка вместо звука хуже
 * честной тишины. Отмена приходит сюда же без пути и молчит: человек сам только что её нажал.
 *
 * Имя записи — «Запись, 4 авг 19:25» (#533). Раньше им было `record-1754325912345.m4a`: имя файла,
 * которое Point сам же и придумал, и в «Недавнем» две записи подряд различались только временем
 * под строкой.
 */
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
