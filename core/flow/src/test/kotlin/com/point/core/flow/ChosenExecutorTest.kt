package com.point.core.flow

import com.point.core.model.ActionYield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ChosenExecutorTest {

    private val onPhone = ActionYield.Same("текст всех страниц · на телефоне")

    @Test
    fun `здешний исполнитель — обещание как есть`() {
        assertSame(onPhone, promiseOfChosen(onPhone, RealizerMeta(kind = RealizerKind.LOCAL)))
        assertSame(onPhone, promiseOfChosen(onPhone, null))
    }

    @Test
    fun `компьютер, который отправит дальше, — так и сказано`() {

        // #1413: в режиме «делай лучшее» документ уходил на компьютер и в сервис, а кнопка
        // обещала «на телефоне».
        val told = promiseOfChosen(onPhone, RealizerMeta(kind = RealizerKind.REMOTE, leavesCircle = true))
        val note = (told as ActionYield.Same).note.orEmpty()

        assertTrue(note, note.startsWith("текст всех страниц"))
        assertTrue(note, note.contains("через компьютер в сервис"))
        assertFalse(note, note.contains("на телефоне"))
    }

    @Test
    fun `компьютер у себя и облако называются каждый своим словом`() {
        val offline = ActionYield.Same("текст документа · без сети")

        val pc = (promiseOfChosen(offline, RealizerMeta(kind = RealizerKind.REMOTE)) as ActionYield.Same).note.orEmpty()
        val cloud = (promiseOfChosen(offline, RealizerMeta(kind = RealizerKind.CLOUD)) as ActionYield.Same).note.orEmpty()

        assertTrue(pc, pc.endsWith("на компьютере"))
        assertTrue(cloud, cloud.endsWith("уйдёт в сервис"))
        assertFalse(cloud, cloud.contains("без сети"))
    }

    @Test
    fun `обещание без слова об исполнителе не трогается`() {
        val plain = ActionYield.Same("снимок уйдёт в сервис")

        assertFalse(promiseNamesHere(plain))
        assertTrue(promiseNamesHere(onPhone))
        assertEquals(plain, promiseOfChosen(plain, RealizerMeta(kind = RealizerKind.REMOTE)))
    }
}
