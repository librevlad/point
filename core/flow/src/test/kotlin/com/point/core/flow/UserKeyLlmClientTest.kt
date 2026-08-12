package com.point.core.flow

import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ResultObject
import com.point.core.model.ScratchRef
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class UserKeyLlmClientTest {

    private val obj = PointObject("id", "text/plain", ScratchRef("/x"), ObjectState(ObjectKind.TEXT))
    private val okBody = """{"choices":[{"message":{"content":"ответ"}}]}"""

    private val store = object : ObjectStore {
        override suspend fun ingest(sourceUri: String, mime: String) = error("unused")
        override suspend fun ingestMultiple(sources: List<String>) = error("unused")
        override suspend fun put(result: ResultObject) = error("unused")
        override suspend fun children(collection: PointObject, limit: Int) = error("unused")
        override suspend fun readText(obj: PointObject, limit: Int) = error("unused")
        override suspend fun newScratchFile(extension: String) =
            ScratchRef(File.createTempFile("point-", ".$extension").apply { deleteOnExit() }.absolutePath)
        override suspend fun clear() = Unit
    }

    private class RememberedFacts : AiFacts {
        val seen = mutableMapOf<String, AiOutcome>()
        override fun all(): Map<String, AiFact> = seen.mapValues { AiFact(it.value, 0L) }
        override fun remember(providerId: String, outcome: AiOutcome) { seen[providerId] = outcome }
    }

    private fun keys(vararg entries: UserAiKey) = object : UserKeyStore {
        override fun keys() = entries.fold(UserAiKeys.NONE) { acc, key -> acc.with(key) }
        override suspend fun save(key: UserAiKey) = Unit
        override suspend fun forget(providerId: String) = Unit
        override suspend fun clear() = Unit
    }

    @Test
    fun `uses the user's endpoint, key and model when set`() = runTest {
        var url = ""; var auth = ""; var body = ""
        val http = object : HttpJson {
            override suspend fun post(u: String, headers: Map<String, String>, b: String): HttpResult {
                url = u; auth = headers["Authorization"].orEmpty(); body = b
                return HttpResult(200, okBody)
            }
        }
        val mine = UserAiKey("own", "sk-user", model = "my-model", baseUrl = "https://my.host/v1")

        val res = UserKeyLlmClient(keys(mine), http, store, RememberedFacts()).run(obj, "hi")

        assertEquals("ответ", File(res.uri.value).readText())
        assertTrue(url.startsWith("https://my.host/v1/chat/completions"))
        assertEquals("Bearer sk-user", auth)
        assertTrue(body.contains("my-model"))
    }

    @Test
    fun `steps aside with a set-a-key error when no key is stored`() = runTest {
        val http = object : HttpJson {
            override suspend fun post(u: String, headers: Map<String, String>, b: String) = HttpResult(200, okBody)
        }
        val e = runCatching { UserKeyLlmClient(keys(), http, store, RememberedFacts()).run(obj, "hi") }
            .exceptionOrNull()
        assertTrue(e?.message?.contains("задайте свой ключ") == true)
    }

    @Test
    fun `отказ по ключу зовёт дверь тем же словом, что на ней написано`() = runTest {
        val http = object : HttpJson {
            override suspend fun post(u: String, headers: Map<String, String>, b: String) = HttpResult(200, okBody)
        }

        val message = runCatching { UserKeyLlmClient(keys(), http, store, RememberedFacts()).run(obj, "hi") }
            .exceptionOrNull()?.message.orEmpty()

        assertTrue("отказ не назвал дверь её именем: $message", message.contains(SETTINGS_TITLE))

        assertTrue("отказ перестал узнаваться как «чинится ключом»", refusalNeedsKey(message))
    }

    @Test
    fun `второй свой ключ подхватывает работу, когда первый отказал`() = runTest {
        val asked = mutableListOf<String>()
        val http = object : HttpJson {
            override suspend fun post(u: String, headers: Map<String, String>, b: String): HttpResult {
                asked += headers["Authorization"].orEmpty()
                return if (asked.size == 1) HttpResult(429, "too many") else HttpResult(200, okBody)
            }
        }
        val facts = RememberedFacts()

        val res = UserKeyLlmClient(
            keys(UserAiKey("openrouter", "sk-or"), UserAiKey("groq", "gsk")),
            http, store, facts,
        ).run(obj, "hi")

        assertEquals("ответ", File(res.uri.value).readText())
        assertEquals(AiOutcome.LIMIT, facts.seen["openrouter"])
        assertEquals(AiOutcome.ANSWERED, facts.seen["groq"])
    }

    @Test
    fun `не подошедший ключ запоминается за своим сервисом`() = runTest {
        val http = object : HttpJson {
            override suspend fun post(u: String, headers: Map<String, String>, b: String) = HttpResult(401, "nope")
        }
        val facts = RememberedFacts()

        runCatching { UserKeyLlmClient(keys(UserAiKey("groq", "gsk")), http, store, facts).run(obj, "hi") }

        assertEquals(AiOutcome.BAD_KEY, facts.seen["groq"])
    }
}
