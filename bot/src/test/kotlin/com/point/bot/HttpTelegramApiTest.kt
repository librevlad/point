package com.point.bot

import com.point.core.flow.Capability
import com.point.core.flow.CapabilityMeta
import com.point.core.flow.Realizer
import com.point.core.model.ActionResult
import com.point.core.model.CapabilityId
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ResultObject
import com.point.core.model.ScratchRef
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.net.URLDecoder
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The full bot HTTP loop over a REAL local stub Telegram server (#92) — proves
 * getUpdates → parse → engine → sendMessage end-to-end without a @BotFather token,
 * the same headless rigor PcServerTest applies to the desktop.
 */
class HttpTelegramApiTest {

    @get:Rule val tmp = TemporaryFolder()

    private lateinit var server: HttpServer
    private val sentMessages = mutableListOf<Map<String, String>>()
    private var updatesJson = """{"ok":true,"result":[]}"""

    @Before fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            val path = ex.requestURI.path
            when {
                path.endsWith("/getUpdates") -> respond(ex, updatesJson)
                path.endsWith("/sendMessage") -> {
                    sentMessages += parseForm(String(ex.requestBody.readBytes()))
                    respond(ex, """{"ok":true}""")
                }
                path.endsWith("/answerCallbackQuery") -> respond(ex, """{"ok":true}""")
                else -> respond(ex, """{"ok":true}""")
            }
        }
        server.start()
    }

    @After fun stop() = server.stop(0)

    private fun apiRoot() = "http://127.0.0.1:${server.address.port}"

    private fun respond(ex: com.sun.net.httpserver.HttpExchange, body: String) {
        val bytes = body.toByteArray()
        ex.sendResponseHeaders(200, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    private fun parseForm(body: String): Map<String, String> =
        body.split("&").filter { it.contains("=") }.associate {
            val (k, v) = it.split("=", limit = 2)
            k to URLDecoder.decode(v, "UTF-8")
        }

    @Test
    fun `getUpdates returns the served json and sendMessage posts chat text and keyboard`() = runTest {
        updatesJson = """{"ok":true,"result":[
            {"update_id":7,"message":{"message_id":1,"chat":{"id":55},"text":"привет"}}
        ]}"""
        val api = HttpTelegramApi("TESTTOKEN", apiRoot())

        val polled = api.getUpdates(0, timeoutSec = 0)
        assertEquals(7L, parseUpdates(polled).single().updateId)

        api.sendMessage(55, "ответ бота", inlineKeyboard(listOf(TgButton("Понять", "cap:understand"))))
        val msg = sentMessages.single()
        assertEquals("55", msg["chat_id"])
        assertEquals("ответ бота", msg["text"])
        assertTrue(msg["reply_markup"]!!.contains("cap:understand"))
    }

    @Test
    fun `the whole loop - a real update drives a real reply with the action keyboard`() = runBlocking {
        updatesJson = """{"ok":true,"result":[
            {"update_id":9,"message":{"message_id":2,"chat":{"id":77},"text":"текст в бот"}}
        ]}"""
        val api = HttpTelegramApi("TESTTOKEN", apiRoot())
        val cap = object : Capability {
            override val id = CapabilityId("echo")
            override val icon = "ai"
            override val meta = CapabilityMeta()
            override fun label(state: ObjectState) = "Эхо"
            override fun accepts(state: ObjectState) = state.kind == ObjectKind.TEXT
            override fun produces(state: ObjectState) = ObjectState(ObjectKind.TEXT)
        }
        val realizer = object : Realizer {
            override val capabilityId = CapabilityId("echo")
            override suspend fun perform(input: PointObject, amendment: String?) =
                ActionResult.Success(ResultObject(ObjectKind.TEXT, "text/plain", input.uri))
        }
        val engine = BotEngine(api, BotRegistry(setOf(cap)), BotResolver(setOf(realizer)), tmp.newFolder("s"))

        parseUpdates(api.getUpdates(0, timeoutSec = 0)).forEach { engine.onUpdate(it) }

        val reply = sentMessages.single { it["chat_id"] == "77" }
        assertTrue(reply["text"]!!.contains("Понял: Текст"))
        assertTrue(reply["reply_markup"]!!.contains("cap:echo"))
    }
}
