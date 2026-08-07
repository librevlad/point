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

            is RelayRpcClient.Asked.Answer ->
                clipPayloadOf(asked.meta, asked.body)?.let { ClipPull.Got(it) } ?: ClipPull.Empty
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
