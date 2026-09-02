package com.point.knock

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.point.core.flow.AccountClient
import com.point.core.flow.AccountStore
import com.point.core.flow.KNOCK_ABOUT_OUTBOX
import com.point.core.flow.KnockMeaning
import com.point.core.flow.KnockTrace
import com.point.core.flow.PcLinks
import com.point.core.flow.PcTransport
import com.point.core.flow.knockMeaning
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.runBlocking

/**
 * Телефон услышал стук и пошёл смотреть, что там (#817).
 *
 * Стук несёт одно слово. Что именно просит компьютер — телефон спрашивает у самого
 * компьютера, минуя Google, и только потом говорит человеку.
 *
 * Ничего не делать здесь тоже правильно: компьютер уснул, интернета нет, просьбу уже забрали.
 * Просьба лежит на компьютере и дождётся — молчание лучше выдуманного уведомления.
 *
 * Но молчание называет себя (#1398). Владелец видел «стук не работает», а сказать, на каком
 * из шести мест он пропал, было нельзя: ни один выход не оставлял следа. Теперь каждый
 * оставляет строку в `knock-log.txt`, а само решение живёт в [knockMeaning] и проверяется
 * без Firebase — служба осталась тонкой дверью.
 */
@AndroidEntryPoint
class KnockService : FirebaseMessagingService() {

    @Inject lateinit var links: PcLinks

    @Inject lateinit var transport: PcTransport

    @Inject lateinit var accounts: AccountStore

    @Inject lateinit var client: AccountClient

    @Inject lateinit var trace: KnockTrace

    override fun onNewToken(token: String) {
        val account = accounts.current()
        if (account == null) {
            // Самое тихое место из всех: адрес выдан раньше, чем человек вошёл, и второй раз
            // Google его не выдаст сам.
            trace.note("адрес выдан, но аккаунта ещё нет — не отправлен")
            return
        }
        val sent = runBlocking { runCatching { client.tellPushAddress(account, token) } }
        trace.note(
            if (sent.isSuccess) "адрес отправлен серверу"
            else "адрес не отправлен: " + (sent.exceptionOrNull()?.message ?: "без причины"),
        )
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val word = message.data[WORD]
        val pc = links.current()
        val waiting = if (word == KNOCK_ABOUT_OUTBOX && pc != null) {
            runBlocking { runCatching { transport.fetchOutbox(pc) }.getOrNull() }
        } else {
            null
        }

        when (val meaning = knockMeaning(word, linked = pc != null, waiting = waiting)) {
            is KnockMeaning.Silent -> trace.note("стук услышан, молчу: " + meaning.why)
            is KnockMeaning.Call -> {
                trace.note("стук услышан, зову: " + meaning.action + " · " + meaning.name)
                Knock.tell(this, phoneRequestNotice(action = meaning.action, name = meaning.name))
            }
        }
    }

    private companion object {
        const val WORD = "knock"
    }
}
