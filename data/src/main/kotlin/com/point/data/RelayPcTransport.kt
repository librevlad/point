package com.point.data

import com.point.core.flow.LinkedPc
import com.point.core.flow.PcOutboxEntry
import com.point.core.flow.PcRemoteAction
import com.point.core.flow.PcSendOutcome
import com.point.core.flow.PcTransport
import com.point.core.flow.RelayRpc
import com.point.core.flow.decodePcCaps
import com.point.core.flow.decodePcOutbox
import com.point.core.flow.decodePcReceiveReply
import com.point.core.flow.encodePcCaps
import com.point.core.model.PointObject
import com.point.core.model.isFileBacked
import java.io.File

/**
 * Единственная дорога до компьютера (#475): ящики сервера Point.
 *
 * Прежде дорог было две — быстрая по локальной сети и запасная через сервер, — и у каждой были
 * свои проверки доступа, свой кадр и свои отказы. Отсюда всё, что нашёл аудит: связь возникала
 * только при совпадении трёх условий разом, вне общей сети не возникала никогда, а совет
 * «проверьте брандмауэр» не помогал, потому что причина была другая. Второй дороги больше нет.
 *
 * Каждая операция — вопрос с ответом ([RelayRpcClient]), включая отправку объекта. Именно ответ
 * отличает «компьютер сделал» от «письмо легло в ящик выключенной машины» (#114, #524).
 */
class RelayPcTransport(
    private val rpc: RelayRpcClient,
) : PcTransport {

    override suspend fun send(
        pc: LinkedPc,
        obj: PointObject,
        fileName: String,
        meta: Map<String, String>,
        action: String?,
    ): PcSendOutcome {
        // Кадр читается целиком: AES-GCM запечатывает всё сразу, а сервер и так не берёт больше
        // 50 МБ за письмо. Гейт по размеру стоит внутри [RelayRpcClient] — ДО сети.
        // У объекта без файла значение лежит прямо в ссылке (#222) — читать нечего и не нужно.
        // Номер карты и номер накладной именно такие: это текст, а не файл.
        val bytes = if (obj.state.kind.isFileBacked) {
            runCatching { File(obj.uri.value).readBytes() }.getOrNull()
                ?: return PcSendOutcome.Unreachable("объект не прочитан")
        } else {
            obj.uri.value.toByteArray(Charsets.UTF_8)
        }
        val frameMeta = buildMap {
            putAll(meta)
            put("name", fileName)
            // Мим объекта без файла — обычный текст: на той стороне он и станет текстом, с
            // которым работают дальше.
            put("mime", if (obj.state.kind.isFileBacked) obj.mime else "text/plain")
            action?.let { put("action", it) }
        }
        return when (val asked = rpc.ask(pc, RelayRpc.OBJECT, frameMeta, bytes)) {
            is RelayRpcClient.Asked.Answer -> sentFrom(asked)
            RelayRpcClient.Asked.Rejected -> PcSendOutcome.Rejected
            is RelayRpcClient.Asked.Failed -> PcSendOutcome.Unreachable(asked.why.name, asked.why)
        }
    }

    /**
     * Что компьютер ответил: объект — если действие его родило, иначе прежнее слово.
     *
     * Порядок проверки важен. Сначала смотрим новые поля: в них ответ с объектом, и разбирать его
     * как текст бессмысленно — там байты файла. Нет их — читаем ответ ровно так, как читали
     * всегда, поэтому старая сборка компьютера продолжает работать без изменений.
     */
    private fun sentFrom(asked: RelayRpcClient.Asked.Answer): PcSendOutcome.Sent {
        if (!com.point.core.flow.PcResultFields.hasObject(asked.meta)) {
            return PcSendOutcome.Sent(decodePcReceiveReply(String(asked.body, Charsets.UTF_8)))
        }
        val f = com.point.core.flow.PcResultFields
        val understood = asked.meta
            .filterKeys { it.startsWith(f.UNDERSTOOD) }
            .mapKeys { (k, _) -> k.removePrefix(f.UNDERSTOOD) }
        return PcSendOutcome.Sent(
            action = com.point.core.flow.PcActionOutcome.Done(asked.meta[f.DETAIL]),
            returned = com.point.core.flow.PcReturned(
                name = asked.meta[f.NAME].orEmpty(),
                mime = asked.meta[f.MIME] ?: "application/octet-stream",
                bytes = asked.body,
                understanding = understood,
            ),
        )
    }

    /** Что умеет компьютер — включая печать и сборку PDF, ради которых человек к нему и идёт. */
    override suspend fun fetchCaps(pc: LinkedPc): List<PcRemoteAction>? =
        answer(pc, RelayRpc.CAPS)?.let { runCatching { decodePcCaps(text(it)) }.getOrNull() }

    override suspend fun fetchOutbox(pc: LinkedPc): List<PcOutboxEntry>? =
        answer(pc, RelayRpc.OUTBOX)?.let { runCatching { decodePcOutbox(text(it)) }.getOrNull() }

    override suspend fun downloadOutboxFile(pc: LinkedPc, id: Int, targetPath: String): Boolean {
        val reply = answer(pc, RelayRpc.FETCH, mapOf("id" to id.toString())) ?: return false
        // Пустое тело с причиной — это отказ компьютера, а не файл нулевой длины: записать его
        // значило бы отдать человеку пустышку под видом результата.
        if (reply.meta.containsKey("error") || reply.body.isEmpty()) return false
        return runCatching {
            File(targetPath).apply { parentFile?.mkdirs() }.writeBytes(reply.body)
            true
        }.getOrDefault(false)
    }

    override suspend fun ackOutbox(pc: LinkedPc, id: Int) {
        answer(pc, RelayRpc.ACK, mapOf("id" to id.toString()))
    }

    override suspend fun pushPhoneCaps(pc: LinkedPc, caps: List<PcRemoteAction>): Boolean =
        answer(pc, RelayRpc.PHONE_CAPS, body = encodePcCaps(caps).toByteArray(Charsets.UTF_8)) != null

    override suspend fun exchangeSecrets(
        pc: LinkedPc,
        mine: com.point.core.flow.SharedSecrets,
    ): com.point.core.flow.SharedSecrets? =
        answer(pc, RelayRpc.SECRETS, body = mine.encode().toByteArray(Charsets.UTF_8))
            ?.let { com.point.core.flow.SharedSecrets.decode(text(it)) }

    /**
     * Ответ или `null`.
     *
     * Здесь причина отказа намеренно схлопывается: эти вопросы задаются фоном, человек их не
     * заказывал, и рассказывать ему про них нечего. Отказ с причиной живёт там, где был тап, —
     * в [send].
     */
    private suspend fun answer(
        pc: LinkedPc,
        kind: String,
        meta: Map<String, String> = emptyMap(),
        body: ByteArray = ByteArray(0),
    ): RelayRpcClient.Asked.Answer? = rpc.ask(pc, kind, meta, body) as? RelayRpcClient.Asked.Answer

    private fun text(reply: RelayRpcClient.Asked.Answer) = String(reply.body, Charsets.UTF_8)
}
