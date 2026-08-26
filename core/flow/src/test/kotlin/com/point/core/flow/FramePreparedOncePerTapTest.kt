package com.point.core.flow

import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ResultObject
import com.point.core.model.ScratchRef
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Один тап — одно кодирование кадра (#1245).
 *
 * Цепочка перебирает провайдеров, и каждый строил кадр заново — до полутора десятков раз за
 * один тап с полными списками моделей. Кадр готовится ДО обращения к сети, поэтому даже
 * сервис, отказавший мгновенно (плохой ключ, исчерпанный лимит), стоил человеку полного
 * декодирования и кодирования снимка — секунды процессора и десятки мегабайт на попытку.
 */
class FramePreparedOncePerTapTest {

    @get:Rule val tmp = TemporaryFolder()

    private val facts = TestAiFacts()

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

    /** Готовилка, которая считает, сколько раз её действительно попросили о работе. */
    private class Counting : FrameForModel {
        var encodings = 0
        override fun of(path: String, mime: String): InlineFrame? {
            encodings++
            return InlineFrame(File(path).readText(), mime)
        }
    }

    private val answer = """{"choices":[{"message":{"content":"прочитано"}}]}"""

    /** Что ушло с попытки: кто спрашивал и с чем. */
    private class Sent(val id: String, val body: String)

    /** Первые двое отказывают лимитом, третий отвечает — обычный проход цепочки. */
    private fun chainOf(frames: FrameForModel, attempts: MutableList<Sent>) = FallbackLlmClient(
        listOf("openrouter", "groq", "cerebras").mapIndexed { i, id ->
            val http = object : HttpJson {
                override suspend fun post(url: String, headers: Map<String, String>, body: String): HttpResult {
                    attempts += Sent(id, body)
                    return if (i < 2) HttpResult(429, "too many") else HttpResult(200, answer)
                }
            }
            OpenAiCompatibleClient(
                http, store,
                OpenAiProvider(id, "https://$id/v1", "sk-$id", "vision-model", vision = true, id = id),
                frames,
            )
        },
        facts,
        NetworkAvailability { true },
    )

    private fun photo(name: String, bytes: String): PointObject {
        val file = File(tmp.root, name).apply { writeText(bytes) }
        return PointObject("id", "image/jpeg", ScratchRef(file.absolutePath), ObjectState(ObjectKind.IMAGE))
    }

    @Test
    fun `цепочка из трёх провайдеров кодирует снимок один раз`() = runTest {
        val counting = Counting()
        val attempts = mutableListOf<Sent>()

        chainOf(counting.oncePerPath(), attempts).run(photo("frame.jpg", "кадр"), "прочитай")

        assertEquals(listOf("openrouter", "groq", "cerebras"), attempts.map { it.id })
        assertEquals(1, counting.encodings)
    }

    @Test
    fun `второй вопрос о том же снимке кадр не переделывает — «Понять» спрашивает трижды за тап`() = runTest {
        val counting = Counting()
        val frames = counting.oncePerPath()
        val obj = photo("frame.jpg", "кадр")

        chainOf(frames, mutableListOf()).run(obj, "что это")
        chainOf(frames, mutableListOf()).run(obj, "что ещё")

        assertEquals(1, counting.encodings)
    }

    @Test
    fun `следующий объект получает свой кадр, а не чужую память`() = runTest {
        val counting = Counting()
        val frames = counting.oncePerPath()
        val first = photo("first.jpg", "первый снимок")
        val second = photo("second.jpg", "второй снимок")

        frames.of(first.uri.value, "image/jpeg")
        val prepared = frames.of(second.uri.value, "image/jpeg")

        assertEquals(File(second.uri.value).readText(), prepared!!.base64)
        assertEquals(2, counting.encodings)
    }

    /**
     * Кадр не живёт дольше копии, из которой сделан (#1245).
     *
     * Решение владельца: срок жизни памяти — до `ObjectStore.clear()`. Иначе готовая строка
     * base64 — а она заметно больше самого файла, у которого потолок 15 МБ, — лежала бы в
     * памяти после того, как работа закончилась. Карточка заведена про десятки мегабайт на
     * попытку, и разменять их на те же десятки навсегда было бы не в ту сторону.
     */
    @Test
    fun `копия отпущена — кадр отпущен вместе с ней`() = runTest {
        val counting = Counting()
        val frames = counting.oncePerPath()
        val obj = photo("frame.jpg", "кадр")

        frames.of(obj.uri.value, "image/jpeg")
        frames.forget()
        frames.of(obj.uri.value, "image/jpeg")

        assertEquals(2, counting.encodings)
    }

    /**
     * Сорвавшаяся попытка не превращается в приговор «кадра нет» (#1239).
     *
     * Готовилка отвечает `null` и когда кадра быть не может, и когда попытка не удалась:
     * ужатие тяжёлого снимка падает на нехватке памяти и молча становится `null`. Запомнить
     * такое — значит для всей остальной цепочки и для всех следующих тапов по тому же объекту
     * решить, что картинки нет: запрос уйдёт без неё, послушная модель ответит NO_IMAGE,
     * непослушная сочинит чтение документа, которого не видела.
     */
    @Test
    fun `сорвавшаяся подготовка не помнится — следующий провайдер получает свой кадр`() = runTest {
        val flaky = object : FrameForModel {
            var asks = 0
            override fun of(path: String, mime: String): InlineFrame? {
                asks++
                return if (asks == 1) null else InlineFrame(File(path).readText(), mime)
            }
        }
        val attempts = mutableListOf<Sent>()

        chainOf(flaky.oncePerPath(), attempts).run(photo("frame.jpg", "kadr-v-base64"), "прочитай")

        assertFalse("в сорвавшейся попытке откуда-то взялась картинка", attempts[0].body.contains("kadr-v-base64"))
        assertTrue("второй провайдер снова ушёл без картинки", attempts[1].body.contains("kadr-v-base64"))
        assertTrue("третий провайдер ушёл без картинки", attempts[2].body.contains("kadr-v-base64"))
        assertEquals("кадр готовился заново — удача не запомнилась", 2, flaky.asks)
    }
}
