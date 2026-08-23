package com.point

import com.point.core.flow.AI_PROVIDERS
import com.point.core.flow.CloudScope
import com.point.core.flow.cloudAskConfirm
import com.point.core.flow.cloudAskTitle
import com.point.core.flow.cloudDestination
import com.point.core.model.CapabilityId
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConsentSaysWhatLeavesTest {

    // Имена, которые Point не вправе произнести на экране согласия (#1252): этих исполнителей он
    // выбирает сам — человек их не выбирал и знать о них не обязан. Настоящим набором «кого
    // Point выбирает» список не был и быть не должен: имена тут нарочно короче полных, чтобы
    // ловить подстрокой («Gemini» ловит и «Google Gemini»).
    //
    // Список обязан называть живых: `Unstructured` и `LlamaParse` из него убраны вместе с самими
    // читалками — имя сервиса, которого в продукте нет, ничего не сторожит, зато выдаёт снесённый
    // путь за живой. И обязан называть всех: по AI-провайдерам полноту держит сверка с реестром
    // ниже, а не память; читалки снимка и голоса реестра не имеют и вписаны руками.
    private val pickedByPoint = listOf(
        "Mistral", "OCR.space", "OVH", "Groq", "Gemini", "Qwen", "SambaNova", "Cerebras",
        "Tesseract", "Whisper", "OpenRouter", "Zhipu", "NVIDIA", "ModelScope", "Cloudflare",
        "OpenAI", "Anthropic",
    )

    private val consentSources = listOf(
        "core/flow/src/main/kotlin/com/point/core/flow/CloudDestination.kt",
        "app/src/main/kotlin/com/point/ConsentScreen.kt",
    )

    @Test
    fun `в словах экрана согласия нет имени сервиса, выбранного Point`() {
        val guilty = consentSpeech().flatMap { (where, text) ->
            pickedByPoint.filter { text.contains(it, ignoreCase = true) }.map { "$where: «$it» в «$text»" }
        }

        assertTrue(guilty.joinToString("\n"), guilty.isEmpty())
    }

    /**
     * Назвать вслух можно ровно один сервис — тот, чей ключ человек вписал сам: его имя
     * приходит в вопрос снаружи, из своих ключей. Поэтому оно из текста вычитается, а всё
     * оставшееся Point сказал от себя — и там имени чужого сервиса быть не смеет.
     *
     * Своим сервисом бывает любой из реестра, не только тот, кого вспомнили при написании
     * теста, — потому в роли выбранного человеком проверяется каждый.
     */
    @Test
    fun `вопрос называет только тот сервис, чей ключ вписал человек`() {
        val ids = listOf("drop-link", "ocr", "ocr-cloud", "ai", "translate", "excel", "understand")
        val guilty = ids.flatMap { id ->
            (listOf(null) + AI_PROVIDERS.map { it.name }).flatMap { mine ->
                val text = cloudDestination(CapabilityId(id), aiService = mine)
                val fromPoint = if (mine == null) text else text.replace(mine, "")
                pickedByPoint.filter { fromPoint.contains(it, ignoreCase = true) }
                    .map { "«$id» при своём «$mine»: «$it» в «$text»" }
            }
        }

        assertTrue(guilty.joinToString("\n"), guilty.isEmpty())
    }

    /**
     * Сторож знает всех, а не тех, кого вспомнили (#1252). Реестр провайдеров растёт, и сервис,
     * попавший в него после сторожа, не сторожится ничем: его имя можно вписать в вопрос, и
     * никто не заметит.
     *
     * Из двенадцати сервисов реестра список молчал о семи — Z.ai, NVIDIA, ModelScope,
     * Cloudflare, OpenAI, Anthropic и OpenRouter, — а ключ ко всем ним у Point свой,
     * встроенный. Шесть первых просто забыли; OpenRouter выпал иначе — он был единственным
     * именем, которое тест подставлял как своё для человека, и попасть в запретный список не
     * мог, пока запрет не научился вычитать выбранное человеком.
     */
    @Test
    fun `сторож знает каждый сервис реестра, а не тех, кого вспомнили`() {
        val unguarded = AI_PROVIDERS.map { it.name }
            .filter { name -> pickedByPoint.none { name.contains(it, ignoreCase = true) } }

        assertEquals("реестр знает сервис, а сторож про него — нет", emptyList<String>(), unguarded)
    }

    @Test
    fun `имя своего сервиса AI остаётся в вопросе`() {
        val text = cloudDestination(CapabilityId("ai"), aiService = "OpenRouter")

        assertTrue("адресат не назван: $text", text.contains("OpenRouter"))
    }

    @Test
    fun `вопрос звучит одной мыслью и умещается на экран`() {
        listOf("drop-link", "ocr", "ocr-cloud", "ai").forEach { id ->
            val text = cloudDestination(CapabilityId(id), aiService = "OpenRouter")
            assertTrue("«$id»: ${text.length} знаков — «$text»", text.length <= 120)
        }

        assertTrue(cloudAskTitle(CloudScope.PUBLIC_LINK).contains("ссылке"))
        assertEquals("Выложить", cloudAskConfirm(CloudScope.PUBLIC_LINK))
        assertTrue(cloudAskTitle(CloudScope.MODELS).contains("облако"))
        assertEquals("Разрешить", cloudAskConfirm(CloudScope.MODELS))
    }

    @Test
    fun `сторож правда прочитал оба места, где живёт вопрос`() {

        consentSources.forEach { path ->
            assertTrue("не найден: ${File(root, path).absolutePath}", File(root, path).isFile)
        }
        assertTrue("человеческих строк не нашлось", consentSpeech().size >= 5)
    }

    @Test
    fun `запретное имя ловится в строке и не ловится в комментарии`() {
        val sample = """
            package com.point.sample
            // Первым читает Mistral OCR — и это ровно то, чего человеку знать не надо.
            /** Разбор промаха: Mistral, Франция (ЕС). */
            class Sample {
                fun ok() = "Снимок уйдёт на сервер распознавания и вернётся текстом."
                fun bad() = "Первым читает Mistral OCR (Франция, ЕС)"
                fun worse() = "Снимок уйдёт на сервер Cloudflare Workers AI и вернётся текстом."
            }
        """.trimIndent()

        val said = literalsIn(sample)

        assertEquals(
            listOf(
                "Снимок уйдёт на сервер распознавания и вернётся текстом.",
                "Первым читает Mistral OCR (Франция, ЕС)",
                "Снимок уйдёт на сервер Cloudflare Workers AI и вернётся текстом.",
            ),
            said,
        )

        // Обе утечки видны, а не одна: пока сторож не знал Cloudflare, вторая проходила молча.
        assertEquals(
            listOf(
                "Первым читает Mistral OCR (Франция, ЕС)",
                "Снимок уйдёт на сервер Cloudflare Workers AI и вернётся текстом.",
            ),
            said.filter { line -> pickedByPoint.any { line.contains(it, ignoreCase = true) } },
        )
    }

    private val root: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private fun consentSpeech(): List<Pair<String, String>> = consentSources.flatMap { path ->
        literalsIn(File(root, path).readText())
            .filter { CYRILLIC.containsMatchIn(it) }
            .map { path to it }
    }

    private fun literalsIn(src: String): List<String> =
        LITERAL.findAll(src.replace(BLOCK_COMMENT, " ").replace(LINE_COMMENT, " "))
            .map { it.groupValues[1] }
            .toList()

    private companion object {
        val CYRILLIC = Regex("[а-яёА-ЯЁ]")
        val BLOCK_COMMENT = Regex("""/\*[\s\S]*?\*/""")

        val LINE_COMMENT = Regex("""(?<!:)//[^\n]*""")

        val LITERAL = Regex(""""((?:[^"\\\n]|\\.)*)"""")
    }
}
