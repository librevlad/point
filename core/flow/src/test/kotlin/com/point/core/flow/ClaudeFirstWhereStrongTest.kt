package com.point.core.flow

import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ResultObject
import com.point.core.model.ScratchRef
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Просьба владельца 21.10.2026: Claude идёт первым там, где он силён. */
class ClaudeFirstWhereStrongTest {

    private fun obj(mime: String) = PointObject("id", mime, ScratchRef("/x"), ObjectState(ObjectKind.TEXT))

    private val longPrompt = "a".repeat(CLAUDE_LONG_TEXT_CHARS)

    private fun answering(tag: String, strong: Boolean = false) = object : LlmClient {
        override val serviceId = tag
        override fun strongAt(obj: PointObject, prompt: String) = strong && claudeStrongAt(obj, prompt)
        override suspend fun run(obj: PointObject, prompt: String) =
            ResultObject(ObjectKind.TEXT, "text/markdown", ScratchRef("/out/$tag"))
    }

    private fun chain(vararg providers: LlmClient) =
        FallbackLlmClient(providers.toList(), TestAiFacts(), NetworkAvailability { true })

    @Test
    fun `PDF и длинный текст — работа Claude`() {
        assertTrue(claudeStrongAt(obj("application/pdf"), "short"))
        assertTrue(claudeStrongAt(obj("text/plain"), longPrompt))
        assertFalse("короткая просьба — не повод платить", claudeStrongAt(obj("text/plain"), "short"))
        assertFalse(claudeStrongAt(obj("image/jpeg"), "short"))
    }

    @Test
    fun `на PDF Claude отвечает раньше бесплатных`() = runTest {
        val answer = chain(answering("free"), answering("anthropic", strong = true)).run(obj("application/pdf"), "short")
        assertEquals("/out/anthropic", answer.uri.value)
    }

    @Test
    fun `на коротком тексте порядок прежний — бесплатное первым`() = runTest {
        val answer = chain(answering("free"), answering("anthropic", strong = true)).run(obj("text/plain"), "short")
        assertEquals("/out/free", answer.uri.value)
    }

    @Test
    fun `среди своих ключей Claude первым на длинном документе`() = runTest {
        val keys = UserAiKeys.NONE
            .with(UserAiKey(GROQ_PROVIDER_ID, "gsk_0123456789abcdef"))
            .with(UserAiKey(ANTHROPIC_PROVIDER_ID, "sk-ant-0123456789abcdef"))
        val asked = mutableListOf<String>()
        val http = object : HttpJson {
            override suspend fun post(url: String, headers: Map<String, String>, body: String): HttpResult {
                asked += url
                return HttpResult(200, """{"choices":[{"message":{"content":"ok"}}]}""")
            }
        }
        val store = object : ObjectStore {
            override suspend fun ingest(sourceUri: String, mime: String) = error("unused")
            override suspend fun ingestMultiple(sources: List<String>) = error("unused")
            override suspend fun put(
                result: ResultObject,
                from: PointObject?,
                by: com.point.core.model.CapabilityId?,
            ) = error("unused")
            override suspend fun children(collection: PointObject, limit: Int) = error("unused")
            override suspend fun readText(obj: PointObject, limit: Int) = error("unused")
            override suspend fun newScratchFile(extension: String) =
                ScratchRef(java.io.File.createTempFile("point-", ".$extension").apply { deleteOnExit() }.absolutePath)
            override suspend fun clear() = Unit
        }
        val stored = object : UserKeyStore {
            override fun keys() = keys
            override suspend fun save(key: UserAiKey) = Unit
            override suspend fun forget(providerId: String) = Unit
            override suspend fun clear() = Unit
        }
        val client = UserKeyLlmClient(stored, http, store, TestAiFacts())

        assertTrue(client.strongAt(obj("text/plain"), longPrompt))
        client.run(obj("text/plain"), longPrompt)
        assertTrue("первым спросили ${asked.first()}", asked.first().contains("anthropic"))
    }
}
