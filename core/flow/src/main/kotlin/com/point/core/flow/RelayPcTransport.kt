package com.point.core.flow

import com.point.core.model.PointObject
import com.point.core.model.isFileBacked
import java.io.File

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

        val bytes = if (obj.state.kind.isFileBacked) {
            runCatching { File(obj.uri.value).readBytes() }.getOrNull()
                ?: return PcSendOutcome.Unreachable("объект не прочитан")
        } else {
            obj.uri.value.toByteArray(Charsets.UTF_8)
        }
        val frameMeta = buildMap {
            putAll(meta)
            put("name", fileName)

            put("mime", if (obj.state.kind.isFileBacked) obj.mime else "text/plain")
            action?.let { put("action", it) }
        }
        return when (val asked = rpc.ask(pc, RelayRpc.OBJECT, frameMeta, bytes)) {
            is RelayRpcClient.Asked.Answer -> sentFrom(asked)
            RelayRpcClient.Asked.Parked -> PcSendOutcome.Parked
            RelayRpcClient.Asked.Rejected -> PcSendOutcome.Rejected
            is RelayRpcClient.Asked.Failed -> PcSendOutcome.Unreachable(asked.why.name, asked.why)
        }
    }

    private fun sentFrom(asked: RelayRpcClient.Asked.Answer): PcSendOutcome.Sent {
        val f = com.point.core.flow.PcResultFields
        val understood = asked.meta
            .filterKeys { it.startsWith(f.UNDERSTOOD) }
            .mapKeys { (k, _) -> k.removePrefix(f.UNDERSTOOD) }
        if (!com.point.core.flow.PcResultFields.hasObject(asked.meta)) {
            return PcSendOutcome.Sent(
                decodePcReceiveReply(String(asked.body, Charsets.UTF_8)),
                understanding = understood,
            )
        }
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

    override suspend fun fetchCaps(pc: LinkedPc): List<PcRemoteAction>? =
        answer(pc, RelayRpc.CAPS)?.let { runCatching { decodePcCaps(text(it)) }.getOrNull() }

    override suspend fun fetchOutbox(pc: LinkedPc): List<PcOutboxEntry>? =
        answer(pc, RelayRpc.OUTBOX)?.let { runCatching { decodePcOutbox(text(it)) }.getOrNull() }

    override suspend fun downloadOutboxFile(pc: LinkedPc, id: Int, targetPath: String): Boolean {
        val reply = answer(pc, RelayRpc.FETCH, mapOf("id" to id.toString())) ?: return false

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

    private suspend fun answer(
        pc: LinkedPc,
        kind: String,
        meta: Map<String, String> = emptyMap(),
        body: ByteArray = ByteArray(0),
    ): RelayRpcClient.Asked.Answer? = rpc.ask(pc, kind, meta, body) as? RelayRpcClient.Asked.Answer

    private fun text(reply: RelayRpcClient.Asked.Answer) = String(reply.body, Charsets.UTF_8)
}
