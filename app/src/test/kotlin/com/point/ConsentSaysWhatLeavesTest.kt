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

/**
 * Сторож вопроса об отправке (#560): он говорит, **что уходит и что вернётся**, — и больше ничего.
 *
 * Живой снимок с телефона владельца: тап по действию → «Отправить в облако? Снимок уйдёт на сервер
 * распознавания и вернётся текстом. Первым читает Mistral OCR (Франция, ЕС); не ответит он —
 * следующий по очереди. Кому вообще можно предлагать, вы задаёте в настройке…». Ответ владельца:
 * «мне не интересно, что Mistral во Франции и кто там вообще читает».
 *
 * Причина названа им же: мы много обсуждали приватность как происхождение и адресата — и это по
 * инерции переехало в текст, который человек читает в момент тапа. Там у него другой вопрос:
 * **уходит или нет**. Порядок цепочки, страна сервиса и его обещания — знание продукта о себе;
 * человек находит их после работы (в происхождении результата) и в настройке, куда приходит
 * именно за выбором уровня приватности.
 *
 * **Почему сторож смотрит в исходники, а не только на готовые строки.** Функция может однажды
 * перестать быть единственным местом, где вопрос собирается: у экрана согласия есть собственное
 * умолчание на случай пустой подписи, и именно оно легко разъезжается с общим текстом. Поэтому
 * проверяются и вычисленные ответы, и **строковые литералы** тех двух файлов, где вопрос живёт, —
 * как это делает сторож жаргона (`NoJargonInProductTest`).
 *
 * Комментарии из проверки вырезаются, и абзац выше — доказательство, что это не педантизм: он
 * называет Mistral, потому что без имени не расскажешь, что именно было не так. Сторож, падающий
 * на честном объяснении, учит вычёркивать слова из объяснений, а не из текстов для человека.
 */
class ConsentSaysWhatLeavesTest {

    /**
     * Имена, которых человек не выбирал: их подобрал Point — по замеру, по квоте, по порядку в
     * цепочке. Завтра порядок сменится, и человек об этом не узнает: это не его решение.
     */
    private val pickedByPoint = listOf(
        "Mistral", "OCR.space", "OVH", "Groq", "Gemini", "Qwen", "SambaNova", "Cerebras",
        "Tesseract", "Whisper", "Unstructured", "LlamaParse",
    )

    /** Оба места, где собирается вопрос: общий текст и умолчание самого экрана. */
    private val consentSources = listOf(
        "core/flow/src/main/kotlin/com/point/core/flow/CloudDestination.kt",
        "app/src/main/kotlin/com/point/ConsentScreen.kt",
    )

    // --- вопрос не называет того, кого выбрал Point ---

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

    // --- а имя, которое выбрал человек, остаётся ---

    /**
     * #538 внутри #560 не отменён.
     *
     * Человек сам пришёл на экран ключа, сам выбрал сервис и сам вписал его ключ. Это его решение
     * и его квота — и в момент, когда у него спрашивают согласие отдать объект, назвать сервис
     * по имени значит ответить на его же вопрос, а не рассказать про нашу кухню.
     */
    @Test
    fun `имя своего сервиса AI остаётся в вопросе`() {
        val text = cloudDestination(CapabilityId("ai"), aiService = "OpenRouter")

        assertTrue("адресат не назван: $text", text.contains("OpenRouter"))
    }

    // --- вопрос — одна мысль ---

    @Test
    fun `вопрос звучит одной мыслью и умещается на экран`() {
        listOf("drop-link", "ocr", "ocr-cloud", "ai").forEach { id ->
            val text = cloudDestination(CapabilityId(id), aiService = "OpenRouter")
            assertTrue("«$id»: ${text.length} знаков — «$text»", text.length <= 120)
        }

        // Заголовок и кнопка по-прежнему называют своё обещание: «показать модели» и «выложить в
        // открытый доступ» — разные решения, и #560 их не сливает (#114).
        assertTrue(cloudAskTitle(CloudScope.PUBLIC_LINK).contains("ссылке"))
        assertEquals("Выложить", cloudAskConfirm(CloudScope.PUBLIC_LINK))
        assertTrue(cloudAskTitle(CloudScope.MODELS).contains("облако"))
        assertEquals("Разрешить", cloudAskConfirm(CloudScope.MODELS))
    }

    // --- сторож обязан что-то охранять ---

    @Test
    fun `сторож правда прочитал оба места, где живёт вопрос`() {
        // Опечатка в пути сделала бы проверки выше вечнозелёными — поломка, которой не видно ни в
        // одном прогоне.
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

    // --- чтение исходников ---

    private val root: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    /** Что экран согласия говорит человеку: строковые литералы с кириллицей, без комментариев. */
    private fun consentSpeech(): List<Pair<String, String>> = consentSources.flatMap { path ->
        literalsIn(File(root, path).readText())
            .filter { CYRILLIC.containsMatchIn(it) }
            .map { path to it }
    }

    /**
     * Строковые литералы файла: сперва вырезаются комментарии, потом берётся то, что в кавычках.
     *
     * Комментарии выбрасываются до разбора нарочно — иначе честное объяснение «вот как звучал
     * прежний текст» роняло бы сборку, и слова вычёркивали бы из объяснений.
     */
    private fun literalsIn(src: String): List<String> =
        LITERAL.findAll(src.replace(BLOCK_COMMENT, " ").replace(LINE_COMMENT, " "))
            .map { it.groupValues[1] }
            .toList()

    private companion object {
        val CYRILLIC = Regex("[а-яёА-ЯЁ]")
        val BLOCK_COMMENT = Regex("""/\*[\s\S]*?\*/""")

        /** `//` после двоеточия — это адрес внутри строки, а не комментарий: `https://…`. Съесть
         *  такую строку значило бы тихо перестать её сторожить, а это дороже ложного срабатывания. */
        val LINE_COMMENT = Regex("""(?<!:)//[^\n]*""")

        /** Обычная строка Kotlin: экранированная кавычка внутри учтена, перенос строки — конец. */
        val LITERAL = Regex(""""((?:[^"\\\n]|\\.)*)"""")
    }
}
