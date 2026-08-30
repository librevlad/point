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

class RelayRequests(
    private val remoteActions: () -> List<PcRemoteAction>,
    private val outbox: Outbox,
    private val onPhoneCaps: (List<PcRemoteAction>) -> Unit,
    private val clipboardGet: () -> ClipboardPayload?,
    private val clipboardSet: (ClipboardPayload) -> Unit,

    private val onObject: (name: String, mime: String, meta: Map<String, String>, bytes: ByteArray, action: String?, askedAgoMs: Long) -> com.point.core.model.ActionResult?,

    private val onSecrets: (com.point.core.flow.SharedSecrets) -> com.point.core.flow.SharedSecrets = { it },
    private val log: (String) -> Unit = {},

    private val seen: SeenLetters? = null,
) {

    class Reply(val meta: Map<String, String> = emptyMap(), val body: ByteArray = ByteArray(0))

    private fun replyFor(result: com.point.core.model.ActionResult?): Reply {
        val born = (result as? com.point.core.model.ActionResult.Success)?.result
        val file = born?.let { java.io.File(it.uri.value).takeIf(java.io.File::isFile) }
        if (born == null || file == null) {
            // Знание из Done едет телефону теми же understood-полями, что и объект-результат:
            // перенос не теряет понятое (PC2; аудит 2026-08-09).
            val understood = packedForTravel((result as? com.point.core.model.ActionResult.Done)?.findings?.metadata.orEmpty())
                .mapKeys { (k, _) -> com.point.core.flow.PcResultFields.UNDERSTOOD + k }
            return Reply(
                meta = understood,
                body = encodePcReceiveReply(com.point.core.flow.pcActionOutcomeOf(result)).toByteArray(Charsets.UTF_8),
            )
        }
        val understood = packedForTravel(born.metadata)
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

    /**
     * [askedAgoMs] — сколько письмо пролежало в ящике (#1321). Просьбу, которую забрали
     * позже, чем попросивший перестал ждать, срочным ответом не догнать: исполнитель об
     * этом должен знать, чтобы отправить исход очередью, а не кадром в никуда.
     */
    fun answer(kind: String, meta: Map<String, String>, bytes: ByteArray, askedAgoMs: Long = 0): Reply? = when (kind) {
        RelayRpc.OBJECT -> {
            val letterId = meta[RelayRpc.ID].orEmpty()
            if (letterId.isNotBlank() && seen?.firstTime(letterId) == false) {
                // Сервер доставляет «хотя бы раз» — повтор письма не рождает второй объект.
                log("письмо уже приносили — принято не будет")
                Reply(
                    body = encodePcReceiveReply(com.point.core.flow.PcActionOutcome.Done("уже получено"))
                        .toByteArray(Charsets.UTF_8),
                )
            } else {
                val name = meta["name"]?.takeIf { it.isNotBlank() } ?: "объект"

                val understanding = meta - setOf("name", "mime", "action", RelayRpc.KIND, RelayRpc.ID)
                val done = runCatching {
                    onObject(
                        name,
                        meta["mime"] ?: "application/octet-stream",
                        understanding,
                        bytes,
                        meta["action"],
                        askedAgoMs,
                    )
                }.getOrElse { e ->
                    log("объект не принят: ${e.javaClass.simpleName}")
                    com.point.core.model.ActionResult.Failure("компьютер не смог принять объект", recoverable = true)
                }
                replyFor(done)
            }
        }

        RelayRpc.CAPS -> Reply(body = encodePcCaps(remoteActions()).toByteArray(Charsets.UTF_8))

        RelayRpc.OUTBOX -> Reply(body = encodePcOutbox(outbox.entries()).toByteArray(Charsets.UTF_8))

        RelayRpc.FETCH -> {
            val entryId = meta["id"]?.toIntOrNull()
            val file = entryId?.let { outbox.file(it) }
            if (file == null) {
                Reply(meta = mapOf("error" to "нет такого объекта"))
            } else {

                val named = outbox.entries().firstOrNull { it.id == entryId }?.meta?.get("name")
                Reply(meta = mapOf("name" to (named ?: file.name)), body = file.readBytes())
            }
        }

        RelayRpc.ACK -> {
            meta["id"]?.toIntOrNull()?.let { runCatching { outbox.remove(it) } }
            Reply()
        }

        RelayRpc.SECRETS -> {

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

            val payload = runCatching { clipboardGet() }.getOrNull()
            if (payload == null) Reply() else Reply(clipMeta(payload), payload.bytes)
        }

        else -> null
    }
}
