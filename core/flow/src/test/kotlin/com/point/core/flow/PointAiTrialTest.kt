package com.point.core.flow

import com.point.core.model.CapabilityId
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ResultObject
import com.point.core.model.ScratchRef
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * AI без своего ключа: пробные действия через сервер Point (#477, владелец 27.11.2026:
 * «Да, так и делаем»). Проверяется то, что обещано человеку: проба есть только в аккаунте и
 * пока действия не кончились, идёт к серверу Point, честно кончается и в вопросе о согласии
 * называет обещание, а не выбранный Point сервис.
 */
class PointAiTrialTest {

    private val account = PointAccount("phone", "пропуск-телефона", "человек@example.com", "Пиксель", DeviceKind.PHONE)

    private val textObj = PointObject("id", "text/plain", ScratchRef("/x"), ObjectState(ObjectKind.TEXT))

    private val answer = "ответ модели"

    private val okBody = """{"choices":[{"message":{"content":"$answer"}}]}"""

    private class Call(val url: String, val headers: Map<String, String>, val body: String)

    private fun http(code: Int, body: String, calls: MutableList<Call> = mutableListOf()) = object : HttpJson {
        override suspend fun post(url: String, headers: Map<String, String>, body2: String): HttpResult {
            calls += Call(url, headers, body2)
            return HttpResult(code, body)
        }
    }

    private val store = object : ObjectStore {
        override suspend fun ingest(sourceUri: String, mime: String) = error("unused")
        override suspend fun ingestMultiple(sources: List<String>) = error("unused")
        override suspend fun put(result: ResultObject, from: PointObject?, by: CapabilityId?) = error("unused")
        override suspend fun children(collection: PointObject, limit: Int) = error("unused")
        override suspend fun readText(obj: PointObject, limit: Int) = error("unused")
        override suspend fun newScratchFile(extension: String) =
            ScratchRef(File.createTempFile("point-", ".$extension").apply { deleteOnExit() }.absolutePath)
        override suspend fun clear() = Unit
    }

    private fun quota(left: Int, available: Boolean = true) =
        MemoryPointAiQuota().apply { remember(PointAiTrial(available = available, left = left, trial = 30)) }

    private fun client(
        http: HttpJson,
        quota: PointAiQuota,
        signedIn: PointAccount? = account,
    ) = PointAiLlmClient({ signedIn }, "https://point.test", http, store, quota)

    @Test
    fun `проба идёт к серверу Point с пропуском устройства`() = runTest {
        val calls = mutableListOf<Call>()

        val result = client(http(200, okBody, calls), quota(30)).run(textObj, "переведи")

        assertEquals("https://point.test/ai/v1/chat/completions", calls.single().url)
        assertEquals("Bearer " + account.deviceToken, calls.single().headers["Authorization"])
        assertEquals("point-trial", JSONObject(calls.single().body).getString("model"))
        assertEquals(answer, File(result.uri.value).readText())
    }

    @Test
    fun `пробы нет без аккаунта, без ключей у сервера и когда действия кончились`() {
        val http = http(200, okBody)

        assertTrue(client(http, quota(5)).configured)
        assertFalse("без входа", client(http, quota(5), signedIn = null).configured)
        assertFalse("у сервера нет ключей", client(http, quota(5, available = false)).configured)
        assertFalse("кончилась", client(http, quota(0)).configured)
        assertFalse("ещё не спрашивали", client(http, MemoryPointAiQuota()).configured)
    }

    @Test
    fun `каждый ответ уменьшает остаток`() = runTest {
        val q = quota(2)

        client(http(200, okBody), q).run(textObj, "переведи")

        assertEquals(1, q.current()?.left)
    }

    @Test
    fun `кончилась — человеку сказано, что делать, и проба больше не предлагается`() = runTest {
        val q = quota(3)
        val body = """{"error":{"type":"trial_over","message":"Пробные AI-действия кончились — задайте свой ключ."}}"""
        val trial = client(http(402, body), q)

        val thrown = runCatching { trial.run(textObj, "переведи") }.exceptionOrNull()

        assertEquals(POINT_AI_TRIAL_OVER, thrown?.message)
        assertEquals(0, q.current()?.left)
        assertFalse(trial.configured)
    }

    @Test
    fun `обещание пробы — не учится на присланном, и режим приватности его слушается`() {
        val privacy = promiseOfService(POINT_AI_SERVICE_ID)

        assertEquals(ReaderPromise.NO_TRAINING, privacy.promise)
        assertTrue(allowedAt(PrivacyLevel.NO_TRAINING, privacy))
        assertFalse("всё на устройстве — проба не идёт", allowedAt(PrivacyLevel.DEVICE_ONLY, privacy))
    }

    @Test
    fun `вопрос о согласии называет путь через Point и обещание, а не сервис`() {
        val text = cloudDestination(CapabilityId("ai"), aiService = POINT_AI_SERVICE_ID)

        assertTrue(text, text.contains("Point"))
        assertTrue(text, text.length <= 120)
        listOf("Groq", "Cloudflare").forEach { assertFalse("назван сервис, выбранный Point: $it", text.contains(it)) }
    }
}
