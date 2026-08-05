package com.point.desktop

import com.point.core.flow.LinkedPc
import com.point.core.flow.Mailbox
import com.point.core.flow.PcSecrets
import com.point.core.flow.PointAccount
import com.point.core.flow.RelayCrypto
import com.point.core.flow.RelayRpc
import com.point.core.flow.decodePcFrame
import com.point.core.flow.encodePcFrame

/**
 * Компьютер разбирает свою почту (#161 v2, переписано в #475).
 *
 * Одна дорога — один поллер. Прежде их было три: объекты, вопросы и буфер жили каждый в своём
 * выдуманном ящике, потому что адрес ящика был производной от токена пары и его можно было
 * выдумывать сколько угодно. С ящиками аккаунта адрес — это имя устройства, ящик у него один, и
 * три поллера отбирали бы письма друг у друга. Вид письма теперь едет в его мете, и разбор
 * поручен одному месту.
 *
 * Компьютер ходит наружу сам, входящих соединений не слушает: своего HTTP-сервера у него больше
 * нет вовсе, а значит нет ни запроса брандмауэра, ни «сделайте сеть частной», ни разговора про
 * порты.
 *
 * **Чьим ключом распечатывать.** Отправитель не назван снаружи — и не может быть назван: имя в
 * открытую превратило бы запечатанное письмо в подписанное сервером. Поэтому ключи соседей по
 * кругу пробуются по очереди, а метка целостности AES-GCM отвечает «этим» или «не этим» без
 * догадок. Заодно это и есть ответ на «от кого»: распечаталось ключом соседа — значит писал он.
 */
class RelayPoller(
    private val serverUrl: String,
    /** Пропуск и свой адрес ящика; `null` — не вошли, разбирать нечего. */
    private val account: () -> PointAccount?,
    /** Соседи по кругу и их открытые ключи — тот же круг, что видит человек на экране. */
    private val peers: () -> List<LinkedPc>,
    private val secrets: PcSecrets,
    private val requests: RelayRequests,
    /**
     * Письмо не открылось ничьим ключом — может быть, круг устарел.
     *
     * Зовётся ДО того, как письмо будет признано чужим: телефон, вошедший после запуска
     * компьютера, иначе получил бы молчание на первое же своё письмо.
     */
    private val onUnknownSender: () -> Unit = {},
    /** Телефон дал о себе знать (#412): экран сам этого узнать не может. */
    private val onContact: () -> Unit = {},
    private val log: (String) -> Unit = {},
    /** Как часто спрашивать ящик. У сервера долгого ожидания нет — цена названа: один запрос в две секунды. */
    private val pollMillis: Long = 2_000,
) {
    @Volatile private var running = false
    private var thread: Thread? = null

    fun start() {
        if (serverUrl.isBlank() || running) return
        running = true
        thread = Thread({ loop() }, "point-mailbox").apply { isDaemon = true }.also { it.start() }
    }

    fun stop() {
        running = false
        thread?.interrupt()
    }

    private fun loop() {
        var lastFailure: String? = null
        while (running) {
            val handled = runCatching { pollOnce() }
                .onFailure { e ->
                    // Виден переход в отказ, а не каждый повтор: молчание хуже, спам тоже (#271).
                    val failure = "${e.javaClass.simpleName}: ${e.message}"
                    if (failure != lastFailure) log("почта не разобрана ($failure), повтор через ${pollMillis / 1000} с")
                    lastFailure = failure
                }
                .onSuccess {
                    if (lastFailure != null) log("почта снова разбирается")
                    lastFailure = null
                }
                .getOrDefault(false)
            // Разобрали письмо — сразу за следующим: очередь может быть непустой, и ждать секунду
            // на каждом письме значило бы растянуть десять писем на десять секунд.
            if (!handled) runCatching { Thread.sleep(if (running) pollMillis else 0) }
        }
    }

    /** Одно письмо; `true` — что-то разобрали (значит стоит спросить ещё раз без паузы). */
    private fun pollOnce(): Boolean {
        val me = account() ?: return false
        val mailbox = Mailbox(serverUrl.trimEnd('/'), { me.deviceToken })
        // Забранное подтверждается внутри [Mailbox] — до разбора: сервер отдаёт старейшее письмо, и
        // нерасшифрованное иначе навсегда встанет головой очереди и заморозит канал (урок #271).
        val blob = mailbox.take(me.deviceId).blob ?: return false

        val opened = tryOpen(blob) ?: run {
            runCatching { onUnknownSender() }
            tryOpen(blob)
        }
        if (opened == null) {
            // Ничьим ключом не открылось: письмо от устройства, чей ключ ещё не приехал в круг, или
            // мусор. Оно уже покинуло очередь, канал жив — молчим, но не притворяемся, что успех.
            log("письмо не открылось ни одним ключом круга — пропущено")
            return true
        }
        val (peer, key, frame) = opened
        onContact()

        val kind = frame.meta[RelayRpc.KIND] ?: return true
        val requestId = frame.meta[RelayRpc.ID].orEmpty()
        val reply = requests.answer(kind, frame.meta, frame.bytes) ?: return true
        send(mailbox, peer, key, requestId, reply)
        return true
    }

    /** Чьим ключом открылось — тот и писал: метка целостности AES-GCM отвечает без догадок. */
    private fun tryOpen(blob: ByteArray): Triple<LinkedPc, ByteArray, com.point.core.flow.PcFrame>? =
        peers().firstNotNullOfOrNull { peer ->
            secrets.sharedWith(peer)?.let { key ->
                runCatching { decodePcFrame(RelayCrypto.open(key, blob)) }.getOrNull()
                    ?.let { Triple(peer, key, it) }
            }
        }

    private fun send(mailbox: Mailbox, peer: LinkedPc, key: ByteArray, requestId: String, reply: RelayRequests.Reply) {
        val sealed = RelayCrypto.seal(
            key,
            encodePcFrame(reply.meta + mapOf(RelayRpc.KIND to RelayRpc.REPLY, RelayRpc.ID to requestId), reply.body),
        )
        val code = mailbox.post(peer.deviceId, sealed)
        if (code != 200) log("ответ телефону не доставлен: сервер ответил $code")
    }
}
