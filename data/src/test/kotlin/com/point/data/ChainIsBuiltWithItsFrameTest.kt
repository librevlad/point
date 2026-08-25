package com.point.data

import com.point.core.flow.AiFact
import com.point.core.flow.AiFacts
import com.point.core.flow.AiOutcome
import com.point.core.flow.CloudPrivacySettings
import com.point.core.flow.FrameForModel
import com.point.core.flow.HttpResult
import com.point.core.flow.InlineFrame
import com.point.core.flow.ObjectStore
import com.point.core.flow.PrivacyLevel
import com.point.core.flow.UserAiKey
import com.point.core.flow.UserAiKeys
import com.point.core.flow.UserKeyStore
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ResultObject
import com.point.core.model.ScratchRef
import com.point.data.di.DataModule
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Готовилка кадра доходит до тех, кто без неё бесполезен (#1239).
 *
 * Клиенты собираются здесь, и здесь же терялся кадр: `UserKeyLlmClient` строился без него,
 * поэтому запрос по снимку уходил чистым текстом — бесплатная попытка человека и ожидание
 * сгорали впустую, а экран ключей записывал исправному ключу «не ответил». Сама сборка и
 * есть место дефекта, поэтому проверяется она, а не только клиент.
 */
class ChainIsBuiltWithItsFrameTest {

    private val store = object : ObjectStore {
        override suspend fun ingest(sourceUri: String, mime: String) = error("не нужно")
        override suspend fun ingestMultiple(sources: List<String>) = error("не нужно")
        override suspend fun put(
            result: ResultObject,
            from: PointObject?,
            by: com.point.core.model.CapabilityId?,
        ) = error("не нужно")
        override suspend fun children(collection: PointObject, limit: Int) = error("не нужно")
        override suspend fun readText(obj: PointObject, limit: Int) = error("не нужно")
        override suspend fun newScratchFile(extension: String) =
            ScratchRef(File.createTempFile("point-", ".$extension").apply { deleteOnExit() }.absolutePath)
        override suspend fun clear() = Unit
    }

    private val myKey = object : UserKeyStore {
        override fun keys() = UserAiKeys.NONE.with(UserAiKey("own", "sk-mine", model = "m", baseUrl = "https://mine/v1"))
        override suspend fun save(key: UserAiKey) = Unit
        override suspend fun forget(providerId: String) = Unit
        override suspend fun clear() = Unit
    }

    private val forgetful = object : AiFacts {
        override fun all(): Map<String, AiFact> = emptyMap()
        override fun remember(providerId: String, outcome: AiOutcome) = Unit
    }

    private val openToEveryone = object : CloudPrivacySettings {
        override fun level() = PrivacyLevel.FREE_FIRST
        override suspend fun setLevel(level: PrivacyLevel) = Unit
    }

    @Test
    fun `по ключу человека снимок уходит вместе с кадром`() = runTest {
        val http = FakeHttpJson { HttpResult(200, """{"choices":[{"message":{"content":"прочитано"}}]}""") }
        val frames = FrameForModel { _, mime -> InlineFrame("0LrQsNC00YA", mime) }
        val photo = PointObject("id", "image/jpeg", ScratchRef("/scratch/x.jpg"), ObjectState(ObjectKind.IMAGE))

        DataModule.userKeyLlmClient(myKey, http, store, forgetful, openToEveryone, frames)
            .run(photo, "прочитай")

        val sent = http.posts.single().body
        assertTrue("кадр не уехал — модель получила один текст", sent.contains("0LrQsNC00YA"))
    }
}
