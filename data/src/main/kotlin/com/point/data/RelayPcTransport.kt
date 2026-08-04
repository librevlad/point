package com.point.data

import com.point.core.flow.PcOutboxEntry
import com.point.core.flow.PcPairing
import com.point.core.flow.PcRemoteAction
import com.point.core.flow.PcSendOutcome
import com.point.core.flow.PcTransport
import com.point.core.flow.RelayCrypto
import com.point.core.flow.decodePcOutbox
import com.point.core.flow.encodePcCaps
import com.point.core.flow.decodePcCaps
import com.point.core.flow.RelayRpc
import com.point.core.flow.RelayTls
import com.point.core.flow.encodePcFrame
import com.point.core.model.PointObject
import java.io.File
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The always-works relay client (#161 v2, phone half — P2b). When the LAN hop can't reach the PC,
 * the object is sealed end-to-end ([RelayCrypto]) and POSTed to the phone→PC mailbox on the blind
 * relay; the PC polls it out (desktop half, P4). Both devices connect OUTBOUND, so no inbound port,
 * NAT, or shared network is needed — LTE, guest Wi-Fi, anywhere. TLS is pinned ([RelayTls]); the
 * relay only ever holds ciphertext. Relay is **send-only** here — everything else stays on LAN.
 */
class RelayPcTransport(
    /** Пропуск устройства в аккаунте (#473): общего пароля приложения больше нет, у каждого свой. */
    private val pass: () -> String?,
    private val connectTimeoutMs: Int = 5_000,
    private val readTimeoutMs: Int = 20_000,
) : PcTransport {

    override suspend fun send(
        pairing: PcPairing,
        obj: PointObject,
        fileName: String,
        meta: Map<String, String>,
        action: String?,
    ): PcSendOutcome = withContext(Dispatchers.IO) {
        val relayUrl = pairing.relay?.trimEnd('/') ?: return@withContext PcSendOutcome.Unreachable("нет релея")
        runCatching {
            // The object travels as a sealed frame: understanding metadata + raw bytes, E2E-encrypted
            // (the relay never sees the token or the plaintext). AES-GCM needs the whole plaintext, so
            // the file is read in full — the relay caps blobs at 50 MB.
            val frameMeta = buildMap {
                putAll(meta)
                put("name", fileName)
                put("mime", obj.mime)
                action?.let { put("action", it) }
            }
            val frame = encodePcFrame(frameMeta, File(obj.uri.value).readBytes())
            val blob = RelayCrypto.seal(pairing.token, frame)
            val mailbox = RelayCrypto.mailboxId(pairing.token, TO_PC)

            val c = URL("$relayUrl/mbx/$mailbox").openConnection() as HttpsURLConnection
            c.sslSocketFactory = RelayTls.socketFactory
            c.requestMethod = "POST"
            c.connectTimeout = connectTimeoutMs
            c.readTimeout = readTimeoutMs
            pass()?.let { c.setRequestProperty("Authorization", "Bearer $it") }
            c.doOutput = true
            c.setFixedLengthStreamingMode(blob.size)
            c.outputStream.use { it.write(blob) }
            val code = c.responseCode
            c.disconnect()
            when (code) {
                // Исход действия через релей узнать неоткуда: файл лёг в почтовый ящик, компьютер
                // заберёт его когда-нибудь сам. Поэтому «доставлено, про исход неизвестно» —
                // и телефон скажет «Отправлено на компьютер», а не «готово» (#114).
                200 -> PcSendOutcome.Sent()
                401, 403 -> PcSendOutcome.Rejected // wrong app secret — a build/config issue, not stale
                else -> PcSendOutcome.Unreachable("relay HTTP $code")
            }
        }.getOrElse { PcSendOutcome.Unreachable(it.message ?: "релей недоступен") }
    }

    /**
     * Пейринг через релей невозможен по построению: ящик адресуется производной от токена, а
     * токен телефон узнаёт как раз из QR. Значит связывание — это чтение QR, а не сетевой вызов;
     * сюда попадает только попытка спариться по адресу, и честный ответ на неё — «не умею».
     */
    override suspend fun pair(host: String, port: Int, deviceName: String): PcPairing? = null

    /** Что умеет компьютер — включая печать и сборку PDF, ради которых человек к нему и идёт. */
    override suspend fun fetchCaps(pairing: PcPairing): List<PcRemoteAction>? =
        rpc.ask(pairing, RelayRpc.CAPS)?.let { reply ->
            runCatching { decodePcCaps(String(reply.body, Charsets.UTF_8)) }.getOrNull()
        }

    override suspend fun fetchOutbox(pairing: PcPairing): List<PcOutboxEntry>? =
        rpc.ask(pairing, RelayRpc.OUTBOX)?.let { reply ->
            runCatching { decodePcOutbox(String(reply.body, Charsets.UTF_8)) }.getOrNull()
        }

    override suspend fun downloadOutboxFile(pairing: PcPairing, id: Int, targetPath: String): Boolean {
        val reply = rpc.ask(pairing, RelayRpc.FETCH, mapOf("id" to id.toString())) ?: return false
        // Пустое тело с причиной — это отказ компьютера, а не файл нулевой длины: записать его
        // значило бы отдать человеку пустышку под видом результата.
        if (reply.meta.containsKey("error") || reply.body.isEmpty()) return false
        return runCatching {
            File(targetPath).apply { parentFile?.mkdirs() }.writeBytes(reply.body)
            true
        }.getOrDefault(false)
    }

    override suspend fun ackOutbox(pairing: PcPairing, id: Int) {
        rpc.ask(pairing, RelayRpc.ACK, mapOf("id" to id.toString()))
    }

    override suspend fun pushPhoneCaps(pairing: PcPairing, caps: List<PcRemoteAction>): Boolean =
        rpc.ask(
            pairing,
            RelayRpc.PHONE_CAPS,
            body = encodePcCaps(caps).toByteArray(Charsets.UTF_8),
        ) != null

    private val rpc = RelayRpcClient(pass)

    private companion object {
        const val TO_PC = "to-pc"
    }
}
