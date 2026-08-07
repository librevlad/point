package com.point.desktop

import com.point.core.flow.Capability
import com.point.core.flow.CapabilityMeta
import com.point.core.flow.DropLink
import com.point.core.flow.Latency
import com.point.core.flow.Realizer
import com.point.core.model.ActionResult
import com.point.core.model.CapabilityId
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64

class DesktopDropLink(
    private val serverUrl: String,

    private val pass: () -> String?,
) : DropLink {

    override suspend fun give(path: String, fileName: String, mime: String): String? =
        withContext(Dispatchers.IO) {
            val base = serverUrl.trimEnd('/').takeIf { it.isNotBlank() && !pass().isNullOrBlank() }
                ?: return@withContext null
            val file = File(path).takeIf { it.isFile } ?: return@withContext null
            if (file.length() > MAX_DROP_BYTES) return@withContext null

            runCatching {
                val connection = (URL("$base/d").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = 10_000
                    readTimeout = 120_000
                    pass()?.let { setRequestProperty("Authorization", "Bearer $it") }

                    setRequestProperty(
                        "X-Drop-Name",
                        Base64.getEncoder().encodeToString(fileName.toByteArray(Charsets.UTF_8)),
                    )
                    setRequestProperty("X-Drop-Mime", mime)
                    setFixedLengthStreamingMode(file.length())
                }
                file.inputStream().use { input -> connection.outputStream.use { out -> input.copyTo(out) } }
                val id = if (connection.responseCode in 200..299) {
                    connection.inputStream.readBytes().decodeToString().trim()
                } else {
                    null
                }
                connection.disconnect()
                id?.takeIf { it.isNotBlank() }?.let { "$base/d/$it" }
            }.getOrNull()
        }

    private companion object {

        const val MAX_DROP_BYTES = 50L * 1024 * 1024
    }
}

class PcDropRealizer(
    private val drop: DropLink,
    private val clipboard: TextClipboard,
) : Realizer {
    override val capabilityId = com.point.core.flow.capabilities.DropLinkCapability.ID

    override val meta = com.point.core.flow.RealizerMeta(kind = com.point.core.flow.RealizerKind.CLOUD)

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult = runCatching {
        val file = File(input.uri.value).takeIf(File::isFile)
            ?: return ActionResult.Failure("Файла объекта нет на диске", recoverable = false)
        val name = input.metadata["name"] ?: file.name
        val link = drop.give(file.absolutePath, name, input.mime)
            ?: return ActionResult.Failure(

                "Ссылку выдать не вышло: войдите в аккаунт на компьютере, проверьте интернет — " +
                    "и учтите, что файл больше 50 МБ ссылкой не отдаётся",
                recoverable = true,
            )
        clipboard.copy(link)
        ActionResult.Done("Ссылка в буфере — живёт сутки")
    }.getOrElse { ActionResult.Failure("Ссылка не выдалась — проверьте интернет и повторите", recoverable = true) }
}
