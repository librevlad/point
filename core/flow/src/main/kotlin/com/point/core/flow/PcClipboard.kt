package com.point.core.flow

class ClipboardPayload(val mime: String, val name: String, val bytes: ByteArray) {
    val isText: Boolean get() = mime.startsWith("text/")
    val isImage: Boolean get() = mime.startsWith("image/")

    fun text(): String = String(bytes, Charsets.UTF_8)

    fun signature(): String = "$mime:${bytes.size}:${bytes.contentHashCode()}"

    companion object {
        fun ofText(text: String) = ClipboardPayload("text/plain", "", text.toByteArray(Charsets.UTF_8))
    }
}

interface PcClipboardSync {

    suspend fun push(pc: LinkedPc, payload: ClipboardPayload): ClipPush

    suspend fun pull(pc: LinkedPc): ClipPull
}

enum class ClipFail {

    TOO_BIG,

    AUTH,
}

sealed interface ClipPush {

    data object Sent : ClipPush

    data object Unreachable : ClipPush

    data class Failed(val why: ClipFail) : ClipPush
}

sealed interface ClipPull {

    data class Got(val payload: ClipboardPayload) : ClipPull

    data object Empty : ClipPull

    data object Unreachable : ClipPull

    data class Failed(val why: ClipFail) : ClipPull
}

fun clipMeta(payload: ClipboardPayload): Map<String, String> =
    mapOf(CLIP_MIME to payload.mime, CLIP_NAME to payload.name)

fun clipPayloadOf(meta: Map<String, String>, bytes: ByteArray): ClipboardPayload? =
    meta[CLIP_MIME]?.takeIf { it.isNotBlank() }
        ?.let { ClipboardPayload(it, meta[CLIP_NAME].orEmpty(), bytes) }

const val CLIP_MIME = "mime"
const val CLIP_NAME = "name"
