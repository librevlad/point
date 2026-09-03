package com.point.desktop

import com.point.core.flow.Capability
import com.point.core.flow.CapabilityMeta
import com.point.core.flow.DropLink
import com.point.core.flow.DropOutcome
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

    /**
     * Отказ называет то, что произошло на самом деле (#1284) — как и на телефоне.
     *
     * Прежде пять разных причин уходили одним `null`, и компьютер отвечал перечислением
     * догадок: «войдите в аккаунт на компьютере, проверьте интернет — и учтите, что файл
     * больше 50 МБ». Живой прогон 17.08.2026: вошёл, интернет есть, файл в 880 раз меньше
     * предела, а сервер просто не ответил — и этого сказано не было.
     */
    override suspend fun give(path: String, fileName: String, mime: String): DropOutcome =
        withContext(Dispatchers.IO) {
            if (serverUrl.isBlank() || pass().isNullOrBlank()) {
                return@withContext DropOutcome.Refused(
                    com.point.core.flow.capabilities.NEEDS_ACCOUNT_FOR_LINK,
                )
            }
            val base = serverUrl.trimEnd('/')
            val file = File(path).takeIf { it.isFile }
                ?: return@withContext DropOutcome.Refused(com.point.core.flow.NO_FILE_FOR_LINK)
            if (file.length() > MAX_DROP_BYTES) {
                return@withContext DropOutcome.Refused(
                    com.point.core.flow.tooHeavyForLink(file.length()),
                )
            }

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
                val code = connection.responseCode
                val answer = if (code in 200..299) {
                    connection.inputStream.readBytes().decodeToString().trim()
                } else {
                    runCatching { connection.errorStream?.readBytes()?.decodeToString()?.trim() }
                        .getOrNull().orEmpty()
                }
                connection.disconnect()
                when {
                    code !in 200..299 -> DropOutcome.Refused(com.point.core.flow.serverRefusedDrop(answer))
                    answer.isBlank() -> DropOutcome.Refused(com.point.core.flow.serverRefusedDrop(""))
                    else -> DropOutcome.Given("$base/d/$answer")
                }

                // Сорвалось по дороге — это видно по чужому отказу и не больше (#1237).
            }.getOrElse { DropOutcome.Refused(com.point.core.flow.CONNECTION_LOST_TEXT) }
        }

    private companion object {

        // Предел один на оба устройства (#861).
        val MAX_DROP_BYTES = com.point.core.flow.MAX_DROP_BYTES
    }
}
