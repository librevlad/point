package com.point.core.flow


class RelayPcClipboardSync(
    private val rpc: RelayRpcClient,
) : PcClipboardSync {

    override suspend fun push(pc: LinkedPc, payload: ClipboardPayload): ClipPush =
        when (val asked = rpc.ask(pc, RelayRpc.CLIP_PUSH, clipMeta(payload), payload.bytes)) {
            is RelayRpcClient.Asked.Answer -> ClipPush.Sent

            // Буфер — не письмо (#672): человеку нужен не путь текста, а текст, готовый к
            // вставке на той стороне. Спящий компьютер этого не сделал, и «дождётся»
            // здесь было бы обещанием впустую: к его пробуждению буфер уже не нужен.
            RelayRpcClient.Asked.Parked -> ClipPush.Unreachable
            RelayRpcClient.Asked.Rejected -> ClipPush.Failed(ClipFail.AUTH)
            is RelayRpcClient.Asked.Failed ->
                if (asked.why == PcUnreachable.TOO_BIG) ClipPush.Failed(ClipFail.TOO_BIG)
                else ClipPush.Unreachable
        }

    override suspend fun pull(pc: LinkedPc): ClipPull =
        when (val asked = rpc.ask(pc, RelayRpc.CLIP_PULL)) {

            is RelayRpcClient.Asked.Answer ->
                clipPayloadOf(asked.meta, asked.body)?.let { ClipPull.Got(it) } ?: ClipPull.Empty
            // Ответа нет — и вставлять нечего: ждать тут нечего тем более.
            RelayRpcClient.Asked.Parked -> ClipPull.Unreachable
            RelayRpcClient.Asked.Rejected -> ClipPull.Failed(ClipFail.AUTH)
            is RelayRpcClient.Asked.Failed ->
                if (asked.why == PcUnreachable.TOO_BIG) ClipPull.Failed(ClipFail.TOO_BIG)
                else ClipPull.Unreachable
        }
}

class RelayCircleClipboard(
    private val links: com.point.core.flow.PcLinks,
    private val sync: com.point.core.flow.PcClipboardSync,
) : com.point.core.flow.CircleClipboard {

    override suspend fun offer(text: String) {
        val pc = links.current() ?: return

        runCatching { sync.push(pc, com.point.core.flow.ClipboardPayload.ofText(text)) }
    }
}
