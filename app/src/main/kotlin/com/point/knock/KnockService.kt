package com.point.knock

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.point.core.flow.AccountClient
import com.point.core.flow.AccountStore
import com.point.core.flow.PcLinks
import com.point.core.flow.PcTransport
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
 */
@AndroidEntryPoint
class KnockService : FirebaseMessagingService() {

    @Inject lateinit var links: PcLinks

    @Inject lateinit var transport: PcTransport

    @Inject lateinit var accounts: AccountStore

    @Inject lateinit var client: AccountClient

    override fun onNewToken(token: String) {
        val account = accounts.current() ?: return
        runBlocking { runCatching { client.tellPushAddress(account, token) } }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        if (message.data[WORD] != OUTBOX) return
        val pc = links.current() ?: return

        val waiting = runBlocking {
            runCatching { transport.fetchOutbox(pc) }.getOrNull().orEmpty()
        }

        // Исход без объекта — слова домой, а не просьба (#1073): звать человека забирать его незачем.
        val first = waiting.firstOrNull { !com.point.core.flow.PcResultFields.outcomeOnly(it.meta) } ?: return

        Knock.tell(
            this,
            phoneRequestNotice(
                action = first.meta[ACTION_LABEL].orEmpty().ifBlank { SOMETHING },
                name = first.meta["name"].orEmpty(),
            ),
        )
    }

    private companion object {
        const val WORD = "knock"
        const val OUTBOX = "outbox"

        /** Название работы человеческими словами кладёт компьютер: он его и показывал. */
        const val ACTION_LABEL = "pc.action.label"

        const val SOMETHING = "сделать кое-что"
    }
}
