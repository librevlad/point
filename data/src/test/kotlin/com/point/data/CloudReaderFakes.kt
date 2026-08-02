package com.point.data

import com.point.core.flow.FrameTransform
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef

/**
 * Подделки для облачных читателей страницы (#280) — сеть и Android-декод за интерфейсами, поэтому
 * запрос, разбор ответа и приведение координат проверяются без единого пикселя и без ключа.
 */

/** Записанный POST формы — по нему тест судит, что мы вообще попросили у сервиса. */
class SentForm(val url: String, val headers: Map<String, String>, val parts: List<FormPart>) {
    fun field(name: String): String? =
        parts.filterIsInstance<FormPart.Field>().firstOrNull { it.name == name }?.value

    fun fields(name: String): List<String> =
        parts.filterIsInstance<FormPart.Field>().filter { it.name == name }.map { it.value }

    fun file(name: String): FormPart.Binary? =
        parts.filterIsInstance<FormPart.Binary>().firstOrNull { it.name == name }
}

/** Сеть, которой нет: ответы задаёт тест, а отправленное остаётся для проверки. */
class FakeHttpFiles(
    private val onPost: (SentForm) -> HttpResult = { HttpResult(200, "[]") },
    private val onGet: (String) -> HttpResult = { HttpResult(200, "{}") },
) : HttpFiles {

    val posts = mutableListOf<SentForm>()
    val gets = mutableListOf<String>()

    override suspend fun postMultipart(
        url: String,
        headers: Map<String, String>,
        parts: List<FormPart>,
    ): HttpResult = SentForm(url, headers, parts).let {
        posts += it
        onPost(it)
    }

    override suspend fun get(url: String, headers: Map<String, String>): HttpResult {
        gets += url
        return onGet(url)
    }
}

/** Кадр, приготовленный «к отправке», с заранее известным преобразованием в сырой файл. */
class FakeOutboundFrames(private val frame: OutboundFrame?) : OutboundFrames {
    override suspend fun of(obj: PointObject): OutboundFrame? = frame
}

/**
 * Кадр 1000×800, полученный уменьшением сырого файла вдвое, — значит сырой файл 2000×1600.
 * Ровно на этом и ловится подмена систем координат: перепутанное пространство даст ровно вдвое
 * меньший адрес и на глаз будет выглядеть правдоподобно.
 */
fun sentFrame(
    transform: FrameTransform = FrameTransform(sample = 2, rotationDegrees = 0, uprightWidth = 1000, uprightHeight = 800),
) = OutboundFrame(
    bytes = byteArrayOf(1, 2, 3),
    mime = "image/jpeg",
    fileName = "page.jpg",
    transform = transform,
)

val pageObject = PointObject("id", "image/jpeg", ScratchRef("/page.jpg"), ObjectState(ObjectKind.IMAGE))
