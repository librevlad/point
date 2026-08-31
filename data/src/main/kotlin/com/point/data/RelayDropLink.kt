package com.point.data

import android.util.Base64
import com.point.core.flow.DropLink
import com.point.core.flow.DropOutcome
import com.point.core.flow.NetworkAvailability
import java.io.File
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class RelayDropLink(
    private val relayUrl: String,

    private val pass: () -> String?,

    private val network: NetworkAvailability,
) : DropLink {

    /**
     * Отказ называет то, что произошло на самом деле (#1284).
     *
     * Пять разных причин уходили одним `null`, и человек читал перечисление догадок вместо
     * причины: «войдите в аккаунт, проверьте интернет — и учтите, что файл больше 50 МБ» при
     * живом аккаунте, живом интернете и файле в 880 раз меньше предела. Правда известна
     * здесь — здесь отказ и рождается.
     *
     * Предел проверяется до отправки: пятьдесят пять мегабайт не уезжают на сервер ради того,
     * чтобы он ответил «полно».
     */
    override suspend fun give(path: String, fileName: String, mime: String): DropOutcome =
        withContext(Dispatchers.IO) {
            // Перед выходом наружу — спросить телефон, есть ли сеть вообще (#690, #691).
            if (!network.isAvailable()) {
                return@withContext DropOutcome.Refused(com.point.core.flow.NO_NETWORK_TEXT)
            }
            if (relayUrl.isBlank() || pass().isNullOrBlank()) {
                return@withContext DropOutcome.Refused(com.point.core.flow.capabilities.NEEDS_ACCOUNT_FOR_LINK)
            }
            val base = relayUrl.trimEnd('/')
            val file = File(path).takeIf { it.isFile }
                ?: return@withContext DropOutcome.Refused(com.point.core.flow.NO_FILE_FOR_LINK)
            if (file.length() > MAX_DROP_BYTES) {
                return@withContext DropOutcome.Refused(com.point.core.flow.tooHeavyForLink(file.length()))
            }

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
                val code = c.responseCode
                val answer = if (code in 200..299) {
                    c.inputStream.readBytes().decodeToString().trim()
                } else {
                    runCatching { c.errorStream?.readBytes()?.decodeToString()?.trim() }.getOrNull().orEmpty()
                }
                c.disconnect()
                when {
                    code !in 200..299 -> DropOutcome.Refused(com.point.core.flow.serverRefusedDrop(answer))
                    answer.isBlank() -> DropOutcome.Refused(com.point.core.flow.serverRefusedDrop(""))
                    else -> DropOutcome.Given("$base/d/$answer")
                }

                // Сорвалось по дороге — это видно по чужому отказу и не больше (#1237):
                // сеть телефон подтвердил живой, значит утверждать про неё нечего.
            }.getOrElse { DropOutcome.Refused(com.point.core.flow.CONNECTION_LOST_TEXT) }
        }

    private companion object {

        // Предел один на оба устройства (#861).
        val MAX_DROP_BYTES = com.point.core.flow.MAX_DROP_BYTES
    }
}
