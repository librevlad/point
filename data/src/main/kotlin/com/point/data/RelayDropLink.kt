package com.point.data

import android.util.Base64
import com.point.core.flow.DropLink
import java.io.File
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class RelayDropLink(
    private val relayUrl: String,

    private val pass: () -> String?,
) : DropLink {

    override suspend fun give(path: String, fileName: String, mime: String): String? =
        withContext(Dispatchers.IO) {
            val base = relayUrl.trimEnd('/').takeIf { it.isNotBlank() && !pass().isNullOrBlank() }
                ?: return@withContext null
            val file = File(path).takeIf { it.isFile } ?: return@withContext null
            if (file.length() > MAX_DROP_BYTES) return@withContext null

            runCatching {
                val c = (URL("$base/d").openConnection() as HttpsURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = 10_000
                    readTimeout = 60_000
                    pass()?.let { setRequestProperty("Authorization", "Bearer $it") }

                    setRequestProperty("X-Drop-Name", Base64.encodeToString(fileName.toByteArray(), Base64.NO_WRAP))
                    setRequestProperty("X-Drop-Mime", mime)
                    setFixedLengthStreamingMode(file.length())
                }
                file.inputStream().use { input -> c.outputStream.use { out -> input.copyTo(out) } }
                val id = if (c.responseCode in 200..299) c.inputStream.readBytes().decodeToString().trim() else null
                c.disconnect()
                id?.takeIf { it.isNotBlank() }?.let { "$base/d/$it" }
            }.getOrNull()
        }

    private companion object {

        const val MAX_DROP_BYTES = 50L * 1024 * 1024
    }
}
