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
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The bot loop (#92): message → object → Flow-Graph-as-keyboard; tap → realizer → reply. */
class BotEngineTest {

    @get:Rule val tmp = TemporaryFolder()

    private class Sent(val chatId: Long, val text: String?, val keyboard: String?, val doc: File?)

    private class FakeApi : TelegramApi {
        val sent = mutableListOf<Sent>()
        var acked = 0
        override suspend fun sendMessage(chatId: Long, text: String, keyboard: String?) {
            sent += Sent(chatId, text, keyboard, null)
        }
        override suspend fun sendDocument(chatId: Long, file: File, caption: String?) {
            sent += Sent(chatId, caption, null, file)
        }
        override suspend fun downloadFile(fileId: String, target: File): Boolean {
            target.writeText("downloaded:$fileId"); return true
        }
        override suspend fun answerCallback(callbackId: String) { acked++ }
    }

    // A pure test capability: TEXT → uppercased TEXT, so we exercise the loop without an LLM.
    private val shoutCap = object : Capability {
        override val id = CapabilityId("shout")
        override val icon = "ai"
        override val meta = CapabilityMeta()
        override fun label(state: ObjectState) = "Крикнуть"
        override fun accepts(state: ObjectState) = state.kind == ObjectKind.TEXT
        override fun produces(state: ObjectState) = ObjectState(ObjectKind.TEXT)
    }

    private fun engine(api: TelegramApi): BotEngine {
        val realizer = object : Realizer {
            override val capabilityId = CapabilityId("shout")
            override suspend fun perform(input: PointObject, amendment: String?): ActionResult {
                val out = File(tmp.newFolder(), "out.txt").apply { writeText(File(input.uri.value).readText().uppercase()) }
                return ActionResult.Success(ResultObject(ObjectKind.TEXT, "text/plain", ScratchRef(out.absolutePath)))
            }
        }
        return BotEngine(api, BotRegistry(setOf(shoutCap)), BotResolver(setOf(realizer)), tmp.newFolder("scratch"))
    }

    @Test
    fun `a text message is understood and answered with an action keyboard`() = runTest {
        val api = FakeApi()
        engine(api).onUpdate(TgUpdate(1, message = TgMessage(chatId = 42, messageId = 5, text = "привет мир")))

        val reply = api.sent.single()
        assertEquals(42L, reply.chatId)
        assertTrue(reply.keyboard!!.contains("cap:shout"))
        assertTrue(reply.keyboard!!.contains("Крикнуть"))
    }

    @Test
    fun `tapping an action runs the realizer, replies with the result and acks the tap`() = runTest {
        val api = FakeApi()
        val eng = engine(api)
        eng.onUpdate(TgUpdate(1, message = TgMessage(chatId = 42, messageId = 5, text = "привет")))
        api.sent.clear()

        eng.onUpdate(TgUpdate(2, callback = TgCallback("cb1", "cap:shout", chatId = 42, messageId = 6)))

        assertEquals(1, api.acked)
        assertTrue(api.sent.any { it.text?.contains("ПРИВЕТ") == true })
    }

    @Test
    fun `a file message is downloaded, classified and answered`() = runTest {
        val api = FakeApi()
        engine(api).onUpdate(
            TgUpdate(1, message = TgMessage(chatId = 42, messageId = 5, fileId = "doc1", mime = "text/plain", fileName = "z.txt")),
        )
        val reply = api.sent.single()
        assertTrue(reply.keyboard!!.contains("cap:shout")) // classified as TEXT → shout offered
    }
}
