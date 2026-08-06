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
    private val onObject: (name: String, mime: String, meta: Map<String, String>, bytes: ByteArray, action: String?) -> com.point.core.model.ActionResult?,
    /**
     * Обмен ключами сервисов (#589): приехали ключи телефона — слить со своими и вернуть общие.
     *
     * Обмен, а не приём: ответ уезжает обратно, и телефон получает то, что вписано здесь. Один
     * раунд выравнивает оба устройства.
     */
    private val onSecrets: (com.point.core.flow.SharedSecrets) -> com.point.core.flow.SharedSecrets = { it },
    private val log: (String) -> Unit = {},
) {

    /** Ответ телефону: мета и байты (у большинства ответов байт нет). */
    class Reply(val meta: Map<String, String> = emptyMap(), val body: ByteArray = ByteArray(0))

    /** Ответ на письмо; `null` — вид письма незнакомый, отвечать нечем и незачем. */
    /**
     * Ответ телефону: объект, если действие его родило, иначе — прежнее слово.
     *
     * Два разных вида ответа нарочно. Пока объекта нет (напечатали, положили в папку, отказали),
     * ответ остаётся ровно тем, чем был, — то есть старая сборка телефона продолжает его понимать.
     * Объект добавляется новыми полями и байтами: старый телефон не найдёт в них знакомой строки и
     * скажет «Отправлено на компьютер» — это правда, просто без подробностей.
     */
    private fun replyFor(result: com.point.core.model.ActionResult?): Reply {
        val born = (result as? com.point.core.model.ActionResult.Success)?.result
        val file = born?.let { java.io.File(it.uri.value).takeIf(java.io.File::isFile) }
        if (born == null || file == null) {
            return Reply(body = encodePcReceiveReply(com.point.core.flow.pcActionOutcomeOf(result)).toByteArray(Charsets.UTF_8))
        }
        val understood = born.metadata
            .filterKeys { it != "name" }
            .mapKeys { (k, _) -> com.point.core.flow.PcResultFields.UNDERSTOOD + k }
        return Reply(
            meta = understood + mapOf(
                com.point.core.flow.PcResultFields.NAME to (born.metadata["name"] ?: file.name),
                com.point.core.flow.PcResultFields.MIME to born.mime,
                com.point.core.flow.PcResultFields.OUTCOME to com.point.core.flow.PcResultFields.DONE,
            ),
            body = file.readBytes(),
        )
    }

    fun answer(kind: String, meta: Map<String, String>, bytes: ByteArray): Reply? = when (kind) {
        RelayRpc.OBJECT -> {
            val name = meta["name"]?.takeIf { it.isNotBlank() } ?: "объект"
            // Понимание объекта едет с ним, а служебные поля кадра в него не попадают: они про
            // дорогу, а не про объект.
            val understanding = meta - setOf("name", "mime", "action", RelayRpc.KIND, RelayRpc.ID)
            val done = runCatching {
                onObject(name, meta["mime"] ?: "application/octet-stream", understanding, bytes, meta["action"])
            }.getOrElse { e ->
                log("объект не принят: ${e.javaClass.simpleName}")
                com.point.core.model.ActionResult.Failure("компьютер не смог принять объект", recoverable = true)
            }
            replyFor(done)
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

        RelayRpc.SECRETS -> {
            // Ключи в журнал не пишутся ни при какой погоде: сюда смотрят чужими глазами.
            val theirs = com.point.core.flow.SharedSecrets.decode(String(bytes, Charsets.UTF_8))
            val merged = runCatching { onSecrets(theirs) }.getOrDefault(theirs)
            Reply(body = merged.encode().toByteArray(Charsets.UTF_8))
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
