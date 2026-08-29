package com.point.data

import com.point.core.flow.HttpJson
import com.point.core.flow.OCR_SPACE_ACTOR
import com.point.core.flow.OcrSpaceTalk
import com.point.core.flow.ReaderPrivacy
import com.point.core.flow.ReaderPromise
import com.point.core.flow.FRAME_NOT_READY
import com.point.core.flow.withoutKey
import com.point.core.model.PointObject

/**
 * Телефонная половина разговора с OCR.space: собрать кадр и сходить в сеть (#1255).
 *
 * Что говорится сервису и как читается его ответ, знает [OcrSpaceTalk] — одно место на оба
 * устройства. Здесь остаётся то, что у сторон и правда разное: телефон собирает кадр из
 * `Bitmap`, компьютер читает файл с диска.
 */
class OcrSpaceReader(
    private val http: HttpJson,
    private val frames: OutboundFrames,
    private val apiKey: () -> String,
    private val baseUrl: String,
) : CloudTextReader {

    override val reader = OCR_SPACE_ACTOR

    override val privacy = ReaderPrivacy(
        where = "OCR.space (a9t9 software), Германия (ЕС)",
        promise = ReaderPromise.UNKNOWN,
    )

    override val configured = true

    private val root: String get() = baseUrl.ifBlank { OcrSpaceTalk.DEFAULT_URL }.trim()

    override suspend fun read(obj: PointObject): String {
        val key = OcrSpaceTalk.keyOrDemo(apiKey())
        val frame = frames.of(obj) ?: error(FRAME_NOT_READY)

        val res = http.post(
            root,
            mapOf("Content-Type" to OcrSpaceTalk.FORM_TYPE),
            OcrSpaceTalk.form(key, frame.mime, frame.bytes),
        )

        // Ключ не возвращается на экран, даже если сервис вернул его в тексте отказа.
        val body = withoutKey(res.body, key)
        if (res.code !in 200..299) OcrSpaceTalk.refuse(res.code)
        return OcrSpaceTalk.textOf(body)
    }
}
