package com.point.core.flow

import java.net.URL
import java.net.HttpURLConnection

class Mailbox(
    private val base: String,

    private val pass: () -> String?,
    private val connectTimeoutMs: Int = 5_000,
    private val readTimeoutMs: Int = 30_000,
) {

    class Letter(val code: Int, val blob: ByteArray?)

    fun post(deviceId: String, blob: ByteArray): Int = runCatching {
        val c = open("$base/mbx/$deviceId")
        c.requestMethod = "POST"
        c.doOutput = true
        c.setFixedLengthStreamingMode(blob.size)
        c.outputStream.use { it.write(blob) }
        val code = c.responseCode
        c.disconnect()
        code
    }.getOrElse { NETWORK }

    fun take(deviceId: String): Letter = runCatching {
        val c = open("$base/mbx/$deviceId")
        val code = c.responseCode
        val blob = if (code == 200) c.inputStream.readBytes() else null
        val blobId = c.getHeaderField("X-Blob-Id")
        c.disconnect()
        if (blob != null && blobId != null) ack(deviceId, blobId)
        Letter(code, blob)
    }.getOrElse { Letter(NETWORK, null) }

    fun drain(deviceId: String) {
        repeat(MAX_DRAIN) { if (take(deviceId).blob == null) return }
    }

    private fun ack(deviceId: String, blobId: String) {
        runCatching {
            val c = open("$base/mbx/$deviceId/ack?blob=$blobId")
            c.requestMethod = "POST"
            c.doOutput = true
            c.outputStream.use { it.write(ByteArray(0)) }
            c.responseCode
            c.disconnect()
        }
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            pass()?.let { setRequestProperty("Authorization", "Bearer $it") }
        }

    companion object {

        const val NETWORK = -1

        private const val MAX_DRAIN = 8
    }
}
