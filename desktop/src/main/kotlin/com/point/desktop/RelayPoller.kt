package com.point.desktop

import com.point.core.flow.KeptLetters
import com.point.core.flow.LinkedPc
import com.point.core.flow.Mailbox
import com.point.core.flow.PcSecrets
import com.point.core.flow.PointAccount
import com.point.core.flow.RelayCrypto
import com.point.core.flow.RelayRpc
import com.point.core.flow.decodePcFrame
import com.point.core.flow.encodePcFrame

class RelayPoller(
    private val serverUrl: String,

    private val account: () -> PointAccount?,

    private val peers: () -> List<LinkedPc>,
    private val secrets: PcSecrets,
    private val requests: RelayRequests,

    private val letters: KeptLetters,

    private val onUnknownSender: () -> Unit = {},

    /**
     * Кто-то из круга отозвался — и названо, кто именно (#1108): в круге бывает второй
     * компьютер, и его голос за телефон не выдаётся.
     */
    private val onContact: (String) -> Unit = {},
    private val log: (String) -> Unit = {},

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
            val handled = runCatching { once() }
                .onFailure { e ->

                    val failure = "${e.javaClass.simpleName}: ${e.message}"
                    if (failure != lastFailure) log("почта не разобрана ($failure), повтор через ${pollMillis / 1000} с")
                    lastFailure = failure
                }
                .onSuccess {
                    if (lastFailure != null) log("почта снова разбирается")
                    lastFailure = null
                }
                .getOrDefault(false)

            if (!handled) runCatching { Thread.sleep(if (running) pollMillis else 0) }
        }
    }

    internal fun once(): Boolean {
        val me = account() ?: return false
        val mailbox = Mailbox(serverUrl.trimEnd('/'), { me.deviceToken })

        // Сначала на диск, потом подтверждение. Пока письмо не сохранено, сервер его не
        // отпускает; сохранённое подтверждается сразу — второй раз его не привезут (#680).
        val answer = mailbox.take(me.deviceId) { letter ->
            letters.keep(letter.id, letter.blob ?: ByteArray(0))
        }

        // Разбор — отдельный шаг. Он читает письмо с диска, поэтому падение на нём
        // (в живом прогоне 2026-08-09 — падение приложения целиком) стоит времени,
        // а не объекта: непонятое письмо дождётся следующего запуска.
        val sorted = sortOut(mailbox, answer.serverNowMs)
        return answer.blob != null || sorted
    }

    /**
     * [serverNowMs] — часы сервера в тот миг, когда ящик отвечал (#1321). Свои часы здесь не
     * спрашиваются вовсе: возраст письма считается той же меркой, какой поставлено время в
     * его имени. Часы ящика достаются и письмам, пролежавшим с прошлого запуска, — они лежат
     * на диске, а разбирают их тем же заходом.
     */
    private fun sortOut(mailbox: Mailbox, serverNowMs: Long?): Boolean {
        var any = false
        letters.waiting().forEach { id ->
            val blob = letters.blob(id)
            if (blob == null) {
                letters.done(id)
                return@forEach
            }
            if (letters.tried(id) >= letters.tries) {
                log("письмо не удаётся разобрать — эта попытка последняя, дальше оно просто полежит")
            }
            handle(mailbox, blob, com.point.core.flow.letterAgeMs(id, serverNowMs))
            letters.done(id)
            any = true
        }
        return any
    }

    /**
     * [askedAgoMs] — сколько письмо пролежало, прежде чем до него дошли руки (#1321). По
     * этому сроку видно, ждёт ли ещё ответа тот, кто просил: выключенный компьютер забирает
     * просьбу часами позже, чем телефон перестал слушать.
     *
     * `null` — возраста не знаем: ящик не ответил вовсе (тогда разбираются письма, оставшиеся
     * на диске с прошлого запуска) либо ответил без своего времени. Нуль вместо незнания
     * означал бы «попросивший ещё слушает» — то самое дорогое заблуждение: исход ушёл бы
     * срочным кадром, а он на мёртвой сети не уходит, и после разбора письма от исхода не
     * осталось бы ничего. Незнание едет дальше незнанием, и исход ложится в очередь, которая
     * лежит на диске ПК и телефона дождётся.
     */
    private fun handle(mailbox: Mailbox, blob: ByteArray, askedAgoMs: Long?) {
        val opened = tryOpen(blob) ?: run {
            runCatching { onUnknownSender() }
            tryOpen(blob)
        }
        if (opened == null) {

            // Молчаливый пропуск прятал причину три часа (2026-08-09): круг может
            // быть пуст, ключ может не сходиться, формат может не читаться — говорим.
            val told = peers().joinToString("; ") { peer ->
                val key = secrets.sharedWith(peer)
                val why = if (key == null) {
                    "нет общего ключа"
                } else {
                    runCatching { decodePcFrame(RelayCrypto.open(key, blob)) }
                        .exceptionOrNull()?.let { "${it.javaClass.simpleName}: ${it.message}" } ?: "?"
                }
                "${peer.name}: $why"
            }.ifEmpty { "круг пуст" }
            log("письмо (${blob.size} байт) не открылось — пропущено [$told]")
            return
        }
        val (peer, key, frame) = opened
        onContact(peer.deviceId)

        val kind = frame.meta[RelayRpc.KIND] ?: return
        val requestId = frame.meta[RelayRpc.ID].orEmpty()
        val reply = requests.answer(kind, frame.meta, frame.bytes, askedAgoMs) ?: return
        send(mailbox, peer, key, requestId, reply)
    }

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
