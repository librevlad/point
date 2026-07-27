package com.point.bot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Telegram wire ↔ domain (#92): parse getUpdates JSON, build inline keyboards. Pure. */
class TelegramDomainTest {

    @Test
    fun `parses a text message update`() {
        val json = """
            {"ok":true,"result":[
              {"update_id":10,"message":{"message_id":5,"chat":{"id":42},"text":"привет"}}
            ]}
        """.trimIndent()
        val updates = parseUpdates(json)
        assertEquals(1, updates.size)
        val u = updates[0]
        assertEquals(10L, u.updateId)
        assertEquals(42L, u.message?.chatId)
        assertEquals("привет", u.message?.text)
        assertNull(u.callback)
    }

    @Test
    fun `parses a photo message taking the largest size`() {
        val json = """
            {"ok":true,"result":[
              {"update_id":11,"message":{"message_id":6,"chat":{"id":42},
               "photo":[{"file_id":"small","file_size":100},{"file_id":"big","file_size":9000}]}}
            ]}
        """.trimIndent()
        val u = parseUpdates(json).single()
        assertEquals("big", u.message?.fileId)
        assertEquals("image/jpeg", u.message?.mime)
    }

    @Test
    fun `parses a document message with its mime and name`() {
        val json = """
            {"ok":true,"result":[
              {"update_id":12,"message":{"message_id":7,"chat":{"id":42},
               "document":{"file_id":"doc1","file_name":"отчёт.pdf","mime_type":"application/pdf"}}}
            ]}
        """.trimIndent()
        val u = parseUpdates(json).single()
        assertEquals("doc1", u.message?.fileId)
        assertEquals("application/pdf", u.message?.mime)
        assertEquals("отчёт.pdf", u.message?.fileName)
    }

    @Test
    fun `parses a callback query`() {
        val json = """
            {"ok":true,"result":[
              {"update_id":13,"callback_query":{"id":"cb1","data":"cap:excel",
               "message":{"message_id":8,"chat":{"id":42}}}}
            ]}
        """.trimIndent()
        val u = parseUpdates(json).single()
        assertEquals("cb1", u.callback?.id)
        assertEquals("cap:excel", u.callback?.data)
        assertEquals(42L, u.callback?.chatId)
    }

    @Test
    fun `builds an inline keyboard - one row of two buttons, callback data per button`() {
        val kb = inlineKeyboard(listOf(TgButton("Перевести", "cap:translate"), TgButton("Понять", "cap:understand")))!!
        assertTrue(kb.contains("\"text\":\"Перевести\""))
        assertTrue(kb.contains("\"callback_data\":\"cap:translate\""))
        assertTrue(kb.contains("inline_keyboard"))
    }

    @Test
    fun `empty button list yields no keyboard`() {
        assertNull(inlineKeyboard(emptyList()))
    }

    @Test
    fun `highestUpdateId drives the next offset, null on empty`() {
        val json = """{"ok":true,"result":[
            {"update_id":100,"message":{"message_id":1,"chat":{"id":1},"text":"a"}},
            {"update_id":103,"message":{"message_id":2,"chat":{"id":1},"text":"b"}}
        ]}"""
        assertEquals(103L, highestUpdateId(json))
        assertNull(highestUpdateId("""{"ok":true,"result":[]}"""))
    }
}
