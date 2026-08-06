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

/**
 * Отдать файл ссылкой — теперь и с компьютера (#585).
 *
 * Самый частый случай на ПК: файл нужно переслать человеку, у которого Point не стоит и не будет
 * стоять. Почта режет вложения, мессенджер жмёт картинку, флешки нет. Ссылка живёт сутки и
 * открывается обычным браузером — получателю не нужно ничего ставить.
 *
 * Отличие от телефонной реализации ровно одно: `java.util.Base64` вместо `android.util.Base64`.
 * Всё остальное — тот же протокол и тот же сервер, поэтому ссылка, выданная компьютером, живёт по
 * тем же правилам, что и выданная телефоном.
 */
class DesktopDropLink(
    private val serverUrl: String,
    /** Пропуск этого компьютера в аккаунте: без входа отдавать ссылку некуда и нечем. */
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
                    // Имя едет в base64: в заголовке HTTP кириллица превращается в мусор, а
                    // «отчёт.pdf» обязан остаться отчётом и у получателя.
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
        /** Столько же, сколько принимает сервер: больше он всё равно отрежет. */
        const val MAX_DROP_BYTES = 50L * 1024 * 1024
    }
}

/**
 * Ссылка кладётся в буфер компьютера — оттуда её и вставляют в письмо или чат.
 *
 * Отдельного объекта из неё не делается намеренно: на телефоне ссылка становится объектом, потому
 * что с ней там есть что делать (QR, поделиться, открыть). На компьютере человек уже стоит в том
 * приложении, куда её вставит, и лишний экран между «дать» и «вставить» — это шаг в никуда.
 */
class PcDropRealizer(
    private val drop: DropLink,
    private val clipboard: TextClipboard,
) : Realizer {
    override val capabilityId = com.point.core.flow.capabilities.DropLinkCapability.ID

    /** Уходит к чужому сервису, и это сказано вслух: телефон спросит согласие ДО
     *  отправки — там, где человек, а не здесь (контракт, граница молчаливого выбора). */
    override val meta = com.point.core.flow.RealizerMeta(kind = com.point.core.flow.RealizerKind.CLOUD)

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult = runCatching {
        val file = File(input.uri.value).takeIf(File::isFile)
            ?: return ActionResult.Failure("Файла объекта нет на диске", recoverable = false)
        val name = input.metadata["name"] ?: file.name
        val link = drop.give(file.absolutePath, name, input.mime)
            ?: return ActionResult.Failure(
                // Причин ровно три, и человеку важна не та, что случилась, а что делать дальше.
                "Ссылку выдать не вышло: войдите в аккаунт на компьютере, проверьте интернет — " +
                    "и учтите, что файл больше 50 МБ ссылкой не отдаётся",
                recoverable = true,
            )
        clipboard.copy(link)
        ActionResult.Done("Ссылка в буфере — живёт сутки")
    }.getOrElse { ActionResult.Failure(it.message ?: "Не удалось выдать ссылку", recoverable = true) }
}
