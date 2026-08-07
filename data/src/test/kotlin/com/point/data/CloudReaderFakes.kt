package com.point.data

import com.point.core.flow.FrameTransform
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef

class SentForm(val url: String, val headers: Map<String, String>, val parts: List<FormPart>) {
    fun field(name: String): String? =
        parts.filterIsInstance<FormPart.Field>().firstOrNull { it.name == name }?.value

    fun fields(name: String): List<String> =
        parts.filterIsInstance<FormPart.Field>().filter { it.name == name }.map { it.value }

    fun file(name: String): FormPart.Binary? =
        parts.filterIsInstance<FormPart.Binary>().firstOrNull { it.name == name }
}

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

class SentJson(val url: String, val headers: Map<String, String>, val body: String)

class FakeHttpJson(private val onPost: (SentJson) -> HttpResult = { HttpResult(200, "{}") }) : HttpJson {

    val posts = mutableListOf<SentJson>()

    override suspend fun post(url: String, headers: Map<String, String>, body: String): HttpResult =
        SentJson(url, headers, body).let {
            posts += it
            onPost(it)
        }
}

class FakeOutboundFrames(private val frame: OutboundFrame?) : OutboundFrames {
    override suspend fun of(obj: PointObject): OutboundFrame? = frame
}

fun sentFrame(
    transform: FrameTransform = FrameTransform(sample = 2, rotationDegrees = 0, uprightWidth = 1000, uprightHeight = 800),
) = OutboundFrame(
    bytes = byteArrayOf(1, 2, 3),
    mime = "image/jpeg",
    fileName = "page.jpg",
    transform = transform,
)

val pageObject = PointObject("id", "image/jpeg", ScratchRef("/page.jpg"), ObjectState(ObjectKind.IMAGE))
