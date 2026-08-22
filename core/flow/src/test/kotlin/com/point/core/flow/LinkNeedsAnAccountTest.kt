package com.point.core.flow

import com.point.core.flow.capabilities.DropLinkCapability
import com.point.core.flow.capabilities.NEEDS_ACCOUNT_FOR_LINK
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ссылку выдаёт сервер Point, а он знает только своих (#1022, решение владельца 21.08.2026).
 *
 * Без аккаунта человек проходил экран согласия «файл уедет на сервер Point», ждал загрузку и
 * получал отказ чужими словами — про связь и про размер. Действие остаётся на месте, но
 * причину называет само и сразу.
 */
class LinkNeedsAnAccountTest {

    private val file = ObjectState(ObjectKind.IMAGE)

    @Test fun `без аккаунта действие остаётся на месте`() {
        assertTrue(
            "действие спрятано — человек не узнает, чего ему не хватает",
            DropLinkCapability { false }.accepts(file),
        )
    }

    @Test fun `без аккаунта действие само называет причину`() {
        assertEquals(NEEDS_ACCOUNT_FOR_LINK, DropLinkCapability { false }.wontWorkNow(file))
    }

    @Test fun `с аккаунтом причины нет и всё идёт как прежде`() {
        assertNull(DropLinkCapability { true }.wontWorkNow(file))
    }

    @Test fun `причина зовёт войти и называет, кто выдаёт ссылку`() {
        assertTrue("человека не зовут войти", "Войдите" in NEEDS_ACCOUNT_FOR_LINK)
        assertTrue("не сказано, кто выдаёт ссылку", "сервер Point" in NEEDS_ACCOUNT_FOR_LINK)

        // Чужих причин в ответе нет: ни про связь, ни про размер файла (#1022).
        assertTrue("причина гадает про связь", "интернет" !in NEEDS_ACCOUNT_FOR_LINK)
        assertTrue("причина гадает про размер", "МБ" !in NEEDS_ACCOUNT_FOR_LINK)
    }

    @Test fun `аккаунт спрашивается на каждый вопрос, а не запоминается однажды`() {
        var signed = false
        val capability = DropLinkCapability { signed }
        assertEquals(NEEDS_ACCOUNT_FOR_LINK, capability.wontWorkNow(file))

        signed = true
        assertNull("человек вошёл, а действие помнит прежнее", capability.wontWorkNow(file))
    }

    @Test fun `общий словарь спрашивает про аккаунт то же самое`() {
        val fromDictionary = com.point.core.flow.capabilities.sharedCapabilities(signedIn = { false })
            .first { it.id == DropLinkCapability.ID }

        assertEquals(NEEDS_ACCOUNT_FOR_LINK, fromDictionary.wontWorkNow(file))
    }
}
