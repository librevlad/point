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

/**
 * Спросить компьютер через ящики сервера и дождаться ответа (#161, переписано в #475).
 *
 * Ящик односторонний, поэтому «вопрос» и «ответ» — два письма: телефон кладёт вопрос в ящик
 * компьютера и ждёт свой ответ в своём. Через это ходит ВСЁ — объект, буфер, вопросы о
 * возможностях: второго пути в проекте больше нет, а значит и второй логики отказов.
 *
 * Отказ различается по причине, а не сводится к `null`. Это и было главной бедой прежней связи:
 * «компьютера нет в круге», «он не запущен» и «сервер молчит» чинятся тремя разными движениями, а
 * человек видел одно слово «недоступен» и шёл проверять брандмауэр, которого дело не касалось.
 */
class RelayRpcClient(
    private val serverUrl: String,
    /** Пропуск и свой адрес в круге; `null` — не вошли, писать не с чего и некому. */
    private val account: () -> PointAccount?,
    private val secrets: PcSecrets,
    /** Кому рассказать, что компьютер ответил (#412): экран сам этого узнать не может. */
    private val monitor: LinkMonitor? = null,
    /** Сколько ждём ответа компьютера, который опрашивает ящик раз в пару секунд. */
    private val waitSeconds: Int = 25,
    /** Пауза между опросами своего ящика — у сервера долгого ожидания нет. */
    private val pollMillis: Long = 1_000,
    private val connectTimeoutMs: Int = 5_000,
) {

    /** Чем кончился вопрос: ответ компьютера либо причина, по которой ответа нет. */
    sealed interface Asked {
        class Answer(val meta: Map<String, String>, val body: ByteArray) : Asked

        /** Сервер не признал это устройство: его отключили из круга. */
        data object Rejected : Asked

        data class Failed(val why: PcUnreachable) : Asked
    }

    /**
     * Вопрос за раз — потому что ящик один.
     *
     * Ответы всех вопросов приходят в ОДИН ящик телефона, и тот, кто спросил вторым, забрал бы
     * чужой ответ и выбросил его: письмо подтверждается сразу, вернуть его в очередь нельзя.
     * Первый ждал бы до конца срока и услышал «компьютер не запущен» про работающий компьютер —
     * ровно ту ложь, ради устранения которой всё и делалось. Компьютер отвечает по одному письму
     * за раз, так что очередь здесь ничего не замедляет.
     */
    private val turn = Mutex()

    /**
     * Задать вопрос и дождаться ответа.
     *
     * Порядок проверок не случаен: сначала то, что видно без сети (нет пропуска, нет ключа, письмо
     * больше предела), потом сеть. Иначе «слишком большой файл» приезжал бы как «недоступен» — тот
     * самый обмен правды на общее слово, из-за которого человек чинил не то.
     */
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
        // Ключа нет — компьютер в круге есть, но объявиться ещё не успел (или вошёл сборкой без
        // ключей). Написать ему нечем: запечатать письмо не на чем, а слать открытым текстом
        // значило бы нарушить единственное обещание, которое Point даёт про сервер.
        val key = secrets.sharedWith(pc) ?: return@withContext Asked.Failed(PcUnreachable.NOT_IN_CIRCLE)

        val requestId = UUID.randomUUID().toString()
        val letter = RelayCrypto.seal(
            key,
            encodePcFrame(meta + mapOf(RelayRpc.KIND to kind, RelayRpc.ID to requestId), body),
        )
        if (letter.size > PC_MAX_LETTER_BYTES) return@withContext Asked.Failed(PcUnreachable.TOO_BIG)

        val mailbox = Mailbox(serverUrl.trimEnd('/'), { me.deviceToken }, connectTimeoutMs)
        mailbox.drain(me.deviceId) // чужие ответы прошлых попыток — чтобы ждать только свой

        when (mailbox.post(pc.deviceId, letter)) {
            200 -> Unit
            401, 403 -> return@withContext Asked.Rejected
            // 404 — сервер не знает такого устройства: компьютер отключили из круга, а телефон
            // помнит его по прошлой жизни. Это не «связь плохая», и чинится оно входом на ПК.
            404 -> return@withContext Asked.Failed(PcUnreachable.NOT_IN_CIRCLE)
            413, 507 -> return@withContext Asked.Failed(PcUnreachable.TOO_BIG)
            // Всё остальное — включая «не дозвонились» — про сервер, а не про компьютер: письмо
            // не легло, и утверждать что-либо про тот конец мы не вправе.
            else -> return@withContext Asked.Failed(PcUnreachable.SERVER_SILENT)
        }

        val deadline = System.nanoTime() + waitSeconds * 1_000_000_000L
        while (System.nanoTime() < deadline) {
            coroutineContext.ensureActive() // отменённый вопрос не должен дожёвывать четверть минуты
            val got = mailbox.take(me.deviceId)
            if (got.code == 401 || got.code == 403) return@withContext Asked.Rejected
            val blob = got.blob
            if (blob == null) {
                delay(pollMillis)
                continue
            }
            val frame = runCatching { decodePcFrame(RelayCrypto.open(key, blob)) }.getOrNull()
                ?: continue // не наше или испорчено — уже подтверждено, ждём дальше
            if (!isOurReply(frame.meta, requestId)) continue
            monitor?.heard()
            return@withContext Asked.Answer(frame.meta, frame.bytes)
        }
        // Письмо легло в ящик, а забирать его некому: «Point для ПК» не запущен. Раньше это
        // выдавалось за доставку, и человек шёл к компьютеру за тем, чего там не случилось.
        Asked.Failed(PcUnreachable.PC_ASLEEP)
    }
}
