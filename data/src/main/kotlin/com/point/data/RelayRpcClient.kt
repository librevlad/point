package com.point.data

import com.point.core.flow.LinkMonitor
import com.point.core.flow.LinkedPc
import com.point.core.flow.Mailbox
import com.point.core.flow.PC_MAX_LETTER_BYTES
import com.point.core.flow.PcSecrets
import com.point.core.flow.PcUnreachable
import com.point.core.flow.PointAccount
import com.point.core.flow.RelayCrypto
import com.point.core.flow.RelayRpc
import com.point.core.flow.decodePcFrame
import com.point.core.flow.encodePcFrame
import com.point.core.flow.isOurReply
import java.util.UUID
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class RelayRpcClient(
    private val serverUrl: String,

    private val account: () -> PointAccount?,
    private val secrets: PcSecrets,

    private val monitor: LinkMonitor? = null,

    private val waitSeconds: Int = 25,

    private val pollMillis: Long = 1_000,
    private val connectTimeoutMs: Int = 5_000,
) {

    sealed interface Asked {
        class Answer(val meta: Map<String, String>, val body: ByteArray) : Asked

        data object Rejected : Asked

        data class Failed(val why: PcUnreachable) : Asked
    }

    private val turn = Mutex()

    suspend fun ask(
        pc: LinkedPc,
        kind: String,
        meta: Map<String, String> = emptyMap(),
        body: ByteArray = ByteArray(0),
    ): Asked = withContext(Dispatchers.IO) { turn.withLock { asked(pc, kind, meta, body) } }

    private suspend fun asked(
        pc: LinkedPc,
        kind: String,
        meta: Map<String, String>,
        body: ByteArray,
    ): Asked = withContext(Dispatchers.IO) {
        val me = account() ?: return@withContext Asked.Failed(PcUnreachable.NOT_IN_CIRCLE)

        val key = secrets.sharedWith(pc) ?: return@withContext Asked.Failed(PcUnreachable.NOT_IN_CIRCLE)

        val requestId = UUID.randomUUID().toString()
        val letter = RelayCrypto.seal(
            key,
            encodePcFrame(meta + mapOf(RelayRpc.KIND to kind, RelayRpc.ID to requestId), body),
        )
        if (letter.size > PC_MAX_LETTER_BYTES) return@withContext Asked.Failed(PcUnreachable.TOO_BIG)

        val mailbox = Mailbox(serverUrl.trimEnd('/'), { me.deviceToken }, connectTimeoutMs)
        mailbox.drain(me.deviceId)

        when (mailbox.post(pc.deviceId, letter)) {
            200 -> Unit
            401, 403 -> return@withContext Asked.Rejected

            404 -> return@withContext Asked.Failed(PcUnreachable.NOT_IN_CIRCLE)
            413, 507 -> return@withContext Asked.Failed(PcUnreachable.TOO_BIG)

            else -> return@withContext Asked.Failed(PcUnreachable.SERVER_SILENT)
        }

        val deadline = System.nanoTime() + waitSeconds * 1_000_000_000L
        while (System.nanoTime() < deadline) {
            coroutineContext.ensureActive()
            val got = mailbox.take(me.deviceId)
            if (got.code == 401 || got.code == 403) return@withContext Asked.Rejected
            val blob = got.blob
            if (blob == null) {
                delay(pollMillis)
                continue
            }
            val frame = runCatching { decodePcFrame(RelayCrypto.open(key, blob)) }.getOrNull()
                ?: continue
            if (!isOurReply(frame.meta, requestId)) continue
            monitor?.heard()
            return@withContext Asked.Answer(frame.meta, frame.bytes)
        }

        Asked.Failed(PcUnreachable.PC_ASLEEP)
    }
}
