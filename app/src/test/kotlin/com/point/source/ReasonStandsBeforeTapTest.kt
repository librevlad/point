package com.point.source

import com.point.core.flow.NOT_IN_ACCOUNT_NOTE
import com.point.core.flow.NOT_IN_ACCOUNT_TEXT
import com.point.core.flow.NO_INTERNET_NOTE
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Причина стоит до тапа, а не отказом после (#897).
 *
 * «Принять файл по ссылке» без входа обещал «дайте ссылку тому, кто пришлёт вам файл», а по
 * тапу открывал пустой экран с красной плашкой «Устройство не в аккаунте. Войдите в
 * настройках» и кнопкой «Отмена». Ровно та же беда, что уже чинилась для сети (#759).
 */
class ReasonStandsBeforeTapTest {

    private fun source(
        what: String? = "что-то полезное",
        network: Boolean = false,
        account: Boolean = false,
    ) = object : ObjectSource {
        override val id = "s"
        override val label = "Источник"
        override val what = what
        override val network = network
        override val account = account
        override fun isAvailable(context: android.content.Context) = true
        override suspend fun request(context: android.content.Context): android.content.Intent? = null
        override suspend fun read(context: android.content.Context, data: android.content.Intent?): Produced? = null
    }

    @Test
    fun `без входа источник, которому нужен аккаунт, говорит об этом заранее`() {
        val note = sourceNote(source(account = true), online = true, signedIn = false)

        assertEquals(NOT_IN_ACCOUNT_NOTE, note)
    }

    @Test
    fun `со входом источник говорит про пользу, а не про вход`() {
        val useful = source(account = true)

        val note = sourceNote(useful, online = true, signedIn = true)

        assertEquals(useful.what, note)
    }

    @Test
    fun `нет сети — беда про сеть важнее беды про вход`() {
        val note = sourceNote(source(network = true, account = true), online = false, signedIn = false)

        assertEquals(NO_INTERNET_NOTE, note)
    }

    @Test
    fun `приёму файла нужен аккаунт, и он это объявляет`() {
        val text = File("src/main/kotlin/com/point/source/ReceiveFileSource.kt").readText()

        assertTrue("источник не объявляет, что ему нужен вход", text.contains("override val account = true"))
    }

    @Test
    fun `экран отказа даёт действие, а не только причину`() {
        val screen = File("src/main/kotlin/com/point/source/ReceiveActivity.kt").readText()

        assertTrue("отказ снова оставлен одной плашкой", screen.contains("Приём не открылся"))
        assertTrue("на экране нечем войти", screen.contains("onSignIn"))
        assertTrue(
            "«Войти» показывается не на своей причине",
            screen.contains("NOT_IN_ACCOUNT_TEXT"),
        )
    }
}
