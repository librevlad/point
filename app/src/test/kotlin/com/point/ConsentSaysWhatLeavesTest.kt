package com.point

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

    private val pickedByPoint = listOf(
        "Mistral", "OCR.space", "OVH", "Groq", "Gemini", "Qwen", "SambaNova", "Cerebras",
        "Tesseract", "Whisper", "Unstructured", "LlamaParse",
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

    @Test
    fun `ни один готовый вопрос не называет того, кого выбрал Point`() {
        val ids = listOf("drop-link", "ocr", "ocr-cloud", "ai", "translate", "excel", "understand")
        val guilty = ids.flatMap { id ->
            listOf(null, "OpenRouter").flatMap { service ->
                val text = cloudDestination(CapabilityId(id), aiService = service)
                pickedByPoint.filter { text.contains(it, ignoreCase = true) }.map { "«$id»: «$it» в «$text»" }
            }
        }

        assertTrue(guilty.joinToString("\n"), guilty.isEmpty())
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
            }
        """.trimIndent()

        val said = literalsIn(sample)

        assertEquals(
            listOf(
                "Снимок уйдёт на сервер распознавания и вернётся текстом.",
                "Первым читает Mistral OCR (Франция, ЕС)",
            ),
            said,
        )
        assertEquals(
            listOf("Первым читает Mistral OCR (Франция, ЕС)"),
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
