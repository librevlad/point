package com.point.desktop

import com.point.core.flow.ClipboardPayload
import com.point.core.flow.PcActionOutcome
import com.point.core.flow.PcRemoteAction
import com.point.core.flow.RelayRpc
import com.point.core.flow.clipMeta
import com.point.core.flow.clipPayloadOf
import com.point.core.flow.decodePcCaps
import com.point.core.flow.encodePcCaps
import com.point.core.flow.encodePcOutbox
import com.point.core.flow.encodePcReceiveReply

/**
 * Что компьютер отвечает телефону (#161, переписано в #475).
 *
 * Раньше это был отдельный поллер со своим ящиком: каналов было три (объекты, вопросы, буфер), и
 * держались они врозь тремя выдуманными адресами. Ящик на сервере аккаунта — один на устройство,
 * поэтому здесь остался чистый ответчик: на вход вид письма и его содержимое, на выход — ответ.
 * Кто письмо принёс и чем его распечатали — забота [RelayPoller].
 *
 * Ответы формируются тем же кодеком, каким телефон их читает: второй правды о том, что умеет
 * компьютер, в проекте не заводится.
 */
class RelayRequests(
    private val remoteActions: () -> List<PcRemoteAction>,
    private val outbox: Outbox,
    private val onPhoneCaps: (List<PcRemoteAction>) -> Unit,
    private val clipboardGet: () -> ClipboardPayload?,
    private val clipboardSet: (ClipboardPayload) -> Unit,
    /**
     * Объект приехал: положить его на конвейер и, если заказано действие, выполнить и назвать
     * исход. `null` — «исход неизвестен», и телефон скажет «Отправлено», а не «готово» (#114).
     */
    private val onObject: (name: String, mime: String, meta: Map<String, String>, bytes: ByteArray, action: String?) -> PcActionOutcome?,
    private val log: (String) -> Unit = {},
) {

    /** Ответ телефону: мета и байты (у большинства ответов байт нет). */
    class Reply(val meta: Map<String, String> = emptyMap(), val body: ByteArray = ByteArray(0))

    /** Ответ на письмо; `null` — вид письма незнакомый, отвечать нечем и незачем. */
    fun answer(kind: String, meta: Map<String, String>, bytes: ByteArray): Reply? = when (kind) {
        RelayRpc.OBJECT -> {
            val name = meta["name"]?.takeIf { it.isNotBlank() } ?: "объект"
            // Понимание объекта едет с ним, а служебные поля кадра в него не попадают: они про
            // дорогу, а не про объект.
            val understanding = meta - setOf("name", "mime", "action", RelayRpc.KIND, RelayRpc.ID)
            val outcome = runCatching {
                onObject(name, meta["mime"] ?: "application/octet-stream", understanding, bytes, meta["action"])
            }.getOrElse { e ->
                log("объект не принят: ${e.javaClass.simpleName}")
                PcActionOutcome.Failed("компьютер не смог принять объект")
            }
            Reply(body = encodePcReceiveReply(outcome).toByteArray(Charsets.UTF_8))
        }

        RelayRpc.CAPS -> Reply(body = encodePcCaps(remoteActions()).toByteArray(Charsets.UTF_8))

        RelayRpc.OUTBOX -> Reply(body = encodePcOutbox(outbox.entries()).toByteArray(Charsets.UTF_8))

        RelayRpc.FETCH -> {
            val entryId = meta["id"]?.toIntOrNull()
            val file = entryId?.let { outbox.file(it) }
            if (file == null) {
                Reply(meta = mapOf("error" to "нет такого объекта"))
            } else {
                // Имя — человеческое, из описи очереди, а не «1.bin» с диска. Телефон берёт его из
                // описи и потому не страдает, но ответ, называющий файл служебным номером, это
                // заряженная ловушка: первый, кто поверит мете, отдаст человеку «1.bin».
                val named = outbox.entries().firstOrNull { it.id == entryId }?.meta?.get("name")
                Reply(meta = mapOf("name" to (named ?: file.name)), body = file.readBytes())
            }
        }

        RelayRpc.ACK -> {
            meta["id"]?.toIntOrNull()?.let { runCatching { outbox.remove(it) } }
            Reply()
        }

        RelayRpc.PHONE_CAPS -> {
            runCatching { onPhoneCaps(decodePcCaps(String(bytes, Charsets.UTF_8))) }
            Reply()
        }

        RelayRpc.CLIP_PUSH -> {
            clipPayloadOf(meta, bytes)?.let { runCatching { clipboardSet(it) } }
            Reply()
        }

        RelayRpc.CLIP_PULL -> {
            // Пустой буфер — законный ответ, и он отличается от молчания: телефон скажет «уже
            // синхронизирован», а не «компьютер не отвечает».
            val payload = runCatching { clipboardGet() }.getOrNull()
            if (payload == null) Reply() else Reply(clipMeta(payload), payload.bytes)
        }

        else -> null
    }
}
