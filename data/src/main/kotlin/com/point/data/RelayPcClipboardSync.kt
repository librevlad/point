package com.point.data

import com.point.core.flow.ClipFail
import com.point.core.flow.ClipPull
import com.point.core.flow.ClipPush
import com.point.core.flow.ClipboardPayload
import com.point.core.flow.LinkedPc
import com.point.core.flow.PcClipboardSync
import com.point.core.flow.PcUnreachable
import com.point.core.flow.RelayRpc
import com.point.core.flow.clipMeta
import com.point.core.flow.clipPayloadOf

/**
 * Общий буфер поверх той же единственной дороги (#161 «общий буфер», переписано в #475).
 *
 * Своего транспорта у буфера больше нет: он задаёт тот же вопрос тем же клиентом, что и объект, —
 * значит и отказы у него те же, и чинятся тем же движением. Раньше здесь была вторая цепочка со
 * своими адресами ящиков, своим кодеком и своим набором ошибок, и расходились они молча.
 *
 * Различимые отказы остаются различимыми (#272): «больше, чем сервер берёт» и «это устройство
 * отключили» повтором не чинятся, и выдавать их за «недоступен» нельзя.
 */
class RelayPcClipboardSync(
    private val rpc: RelayRpcClient,
) : PcClipboardSync {

    override suspend fun push(pc: LinkedPc, payload: ClipboardPayload): ClipPush =
        when (val asked = rpc.ask(pc, RelayRpc.CLIP_PUSH, clipMeta(payload), payload.bytes)) {
            is RelayRpcClient.Asked.Answer -> ClipPush.Sent
            RelayRpcClient.Asked.Rejected -> ClipPush.Failed(ClipFail.AUTH)
            is RelayRpcClient.Asked.Failed ->
                if (asked.why == PcUnreachable.TOO_BIG) ClipPush.Failed(ClipFail.TOO_BIG)
                else ClipPush.Unreachable
        }

    override suspend fun pull(pc: LinkedPc): ClipPull =
        when (val asked = rpc.ask(pc, RelayRpc.CLIP_PULL)) {
            // Ответ без содержимого — это ответ: у компьютера пусто. Выдавать его за
            // недоступность значило бы звать человека чинить работающую связь.
            is RelayRpcClient.Asked.Answer ->
                clipPayloadOf(asked.meta, asked.body)?.let { ClipPull.Got(it) } ?: ClipPull.Empty
            RelayRpcClient.Asked.Rejected -> ClipPull.Failed(ClipFail.AUTH)
            is RelayRpcClient.Asked.Failed ->
                if (asked.why == PcUnreachable.TOO_BIG) ClipPull.Failed(ClipFail.TOO_BIG)
                else ClipPull.Unreachable
        }
}
