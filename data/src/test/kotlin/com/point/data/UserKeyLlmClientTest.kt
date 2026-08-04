package com.point.data

import com.point.core.flow.ObjectStore
import com.point.core.flow.UserAiConfig
import com.point.core.flow.UserKeyStore
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

/** The user's own key as a provider: used when set, steps aside when not. */
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

    private fun keys(config: UserAiConfig?) = object : UserKeyStore {
        override fun read() = config
        override suspend fun save(config: UserAiConfig) = Unit
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
        val config = UserAiConfig("sk-user", "https://my.host/v1", "my-model")

        val res = UserKeyLlmClient(keys(config), http, store).run(obj, "hi")

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
        val e = runCatching { UserKeyLlmClient(keys(null), http, store).run(obj, "hi") }.exceptionOrNull()
        assertTrue(e?.message?.contains("задайте свой ключ") == true)
    }
}
