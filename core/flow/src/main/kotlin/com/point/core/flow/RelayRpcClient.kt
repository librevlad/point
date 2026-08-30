package com.point.core.flow

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

    private val network: NetworkAvailability = NetworkAvailability { true },

    /** Тот же срок, по которому вторая сторона судит, ждут ли ещё её ответа (#1321). */
    private val waitSeconds: Int = (PC_ANSWER_WAIT_MS / 1_000).toInt(),

    private val pollMillis: Long = 1_000,
    private val connectTimeoutMs: Int = 5_000,
) {

    sealed interface Asked {
        class Answer(val meta: Map<String, String>, val body: ByteArray) : Asked

        /** Письмо принято сервером, а ответа не дождались: судьба письма — «ждёт» (#672). */
        data object Parked : Asked

        data object Rejected : Asked

        data class Failed(val why: PcUnreachable) : Asked
    }

    private val turn = Mutex()

    suspend fun ask(
        pc: LinkedPc,
        kind: String,
        meta: Map<String, String> = emptyMap(),
        body: ByteArray = ByteArray(0),
    ): Asked {
        // Перед выходом наружу — спросить телефон, есть ли сеть вообще (#690): нет
        // сети — ни один запрос не уходит, а «до сервера не дозвониться» больше не
        // выясняется перебором сетевых таймаутов по четыре минуты.
        if (!network.isAvailable()) return Asked.Failed(PcUnreachable.NO_NETWORK)
        return withContext(Dispatchers.IO) { turn.withLock { asked(pc, kind, meta, body) } }
    }

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
            // Здесь едет ответ на вопрос, заданный секунду назад, — класть его на диск
            // незачем: если телефон умрёт, он спросит заново. Письмо с объектом,
            // которое нельзя терять, приходит на компьютер, и там оно сохраняется.
            val got = mailbox.take(me.deviceId) { }
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

        // Ответа не дождались, но письмо приняли (post вернул 200) — это судьба письма,
        // а не статус RPC (#672): объект лежит на сервере и дождётся компьютера. Прежнее
        // «компьютер не отвечает» звучало как «не доставлено», и человек отправлял заново.
        Asked.Parked
    }
}
