package com.point

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NoJargonInProductTest {

    private val internals = listOf(
        "Realizer", "Capability", "Resolver", "Enricher", "Recognizer", "Rasterizer",
        "ObjectStore", "LlmClient", "BubblePolicy", "ScratchRef", "PointObject", "ActionResult",
        "реализатор", "реализаци", "капабилити", "резолвер", "энричер",
    )

    private val libraries = listOf(
        "OpenCV", "Tesseract", "ZXing", "MLKit", "ML Kit", "PDFBox", "PdfBox", "Apache POI",
        "OkHttp", "Retrofit", "Hilt", "Dagger", "Robolectric", "Bitmap", "Canvas",
    )

    private val rawMime = Regex("""(?i)\b(application|image|audio|video|text|multipart)/[a-z0-9][a-z0-9.+\-]*""")

    private val mimeHole = Regex("""(?i)\b(mime|mimeType|contentType)\b""")

    private val payloadHole = Regex("""\b(body|json|payload|raw|responseBody|html)\b""")

    private val logShape = Regex("""^[A-Za-z][A-Za-z0-9 .+_\-]{0,20}:\s""")

    @Test
    fun `в словах для человека нет ни имени нашей детали, ни имени библиотеки`() {
        val said = productSpeech()
        val guilty = said.flatMap { line ->
            (internals + libraries).filter { line.text.containsWord(it) }.map { "${line.where}: «$it» в «${line.text}»" }
        }

        assertTrue(guilty.joinToString("\n"), guilty.isEmpty())
    }

    @Test
    fun `в словах для человека нет сырого типа файла`() {
        val guilty = productSpeech().filter { line ->
            rawMime.containsMatchIn(line.text) || line.holes.any { mimeHole.containsMatchIn(it) }
        }

        assertTrue(guilty.joinToString("\n") { "${it.where}: «${it.text}»" }, guilty.isEmpty())
    }

    @Test
    fun `в словах для человека нет куска чужого ответа`() {
        val guilty = productSpeech().filter { line ->
            line.holes.any { it.contains(".take(") && payloadHole.containsMatchIn(it) }
        }

        assertTrue(guilty.joinToString("\n") { "${it.where}: «${it.text}»" }, guilty.isEmpty())
    }

    @Test
    fun `слова для человека не написаны в формате лога`() {
        val guilty = productSpeech().filter { logShape.containsMatchIn(it.text.trim()) }

        assertTrue(guilty.joinToString("\n") { "${it.where}: «${it.text}»" }, guilty.isEmpty())
    }

    @Test
    fun `сторож правда прочитал продукт, а не пустоту`() {

        val files = sources()
        val said = productSpeech()

        assertTrue("исходников не нашлось: ${repo.absolutePath}", files.size > 100)
        assertTrue("человеческих строк не нашлось", said.size > 300)
    }

    @Test
    fun `запретное слово в строке ловится, а в комментарии — нет`() {
        val sample = """
            package com.point.sample
            // Гейт по загрузке OpenCV — иначе реализатор молча ляжет.
            /** Читает страницу Tesseract'ом: это Capability, а не Realizer. */
            class Sample {
                fun ok() = "Страницу не удалось выпрямить — снимите её при ровном свете"
                fun bad() = "Ошибка OpenCV-скана"
            }
        """.trimIndent()

        val said = saidIn("sample.kt", sample)

        assertEquals(
            "из образца взяты не те строки: ${said.map { it.text }}",
            listOf(
                "Страницу не удалось выпрямить — снимите её при ровном свете",
                "Ошибка OpenCV-скана",
            ),
            said.map { it.text },
        )
        assertEquals(
            listOf("Ошибка OpenCV-скана"),
            said.filter { line -> libraries.any { line.text.containsWord(it) } }.map { it.text },
        )
    }

    @Test
    fun `сторож видит нарушение любого из четырёх сортов`() {

        val sample = """
            package com.point.sample
            class Sample {
                fun a() = "Нет доступных реализаций"
                fun b() = "Нет приложения, которое открывает «application/vnd.ms-excel»"
                fun c(json: String) = "Ответ не разобран — ${'$'}{json.take(200)}"
                fun d() = "PDF: неподдерживаемый вход"
                fun e(obj: Any) = "Не открывается «${'$'}{obj.mime}»"
            }
        """.trimIndent()

        val said = saidIn("sample.kt", sample)

        assertTrue("внутреннее слово прошло", said.any { line -> internals.any { line.text.containsWord(it) } })
        assertTrue("сырой тип файла прошёл", said.any { rawMime.containsMatchIn(it.text) })
        assertTrue(
            "кусок чужого ответа прошёл",
            said.any { line -> line.holes.any { it.contains(".take(") && payloadHole.containsMatchIn(it) } },
        )
        assertTrue("формат лога прошёл", said.any { logShape.containsMatchIn(it.text) })
        assertTrue("подстановка типа файла прошла", said.any { line -> line.holes.any { mimeHole.containsMatchIn(it) } })
    }

    private data class Said(val where: String, val text: String, val holes: List<String>)

    private fun sources(): List<File> = listOf("app", "core", "data", "executors")
        .map { File(repo, it) }
        .flatMap { module ->
            module.walkTopDown()
                .onEnter { it.name != "build" }
                .filter { it.isFile && it.extension == "kt" }
                .filter { "/src/main/" in it.path.replace('\\', '/') }
                .toList()
        }

    private fun productSpeech(): List<Said> = sources()
        .flatMap { saidIn(it.toRelativeString(repo), it.readText()) }
        .filter { CYRILLIC.containsMatchIn(it.text) }

    private fun String.containsWord(word: String): Boolean =
        if (word.first() in 'A'..'z') {
            Regex("(?i)(?<![A-Za-z])" + Regex.escape(word) + "(?![A-Za-z])").containsMatchIn(this)
        } else {
            contains(word, ignoreCase = true)
        }

    private fun saidIn(where: String, src: String): List<Said> {
        val out = mutableListOf<Said>()
        var i = 0
        var line = 1
        while (i < src.length) {
            when {
                src[i] == '\n' -> { line++; i++ }
                src.startsWith("//", i) -> { while (i < src.length && src[i] != '\n') i++ }
                src.startsWith("/*", i) -> {
                    var depth = 1
                    i += 2
                    while (i < src.length && depth > 0) {
                        when {
                            src[i] == '\n' -> { line++; i++ }
                            src.startsWith("/*", i) -> { depth++; i += 2 }
                            src.startsWith("*/", i) -> { depth--; i += 2 }
                            else -> i++
                        }
                    }
                }
                src[i] == '\'' -> {
                    i++
                    while (i < src.length && src[i] != '\'') {
                        if (src[i] == '\\') i++
                        i++
                    }
                    i++
                }
                src[i] == '"' -> {
                    val raw = src.startsWith("\"\"\"", i)
                    val text = StringBuilder()
                    val holes = mutableListOf<String>()
                    val startLine = line
                    i += if (raw) 3 else 1
                    while (i < src.length) {
                        if (raw && src.startsWith("\"\"\"", i)) { i += 3; break }
                        if (!raw && src[i] == '"') { i++; break }
                        if (!raw && src[i] == '\\') { text.append(src, i, minOf(i + 2, src.length)); i += 2; continue }
                        if (src[i] == '\n') { line++; text.append('\n'); i++; continue }
                        if (src[i] == '$' && i + 1 < src.length && src[i + 1] == '{') {
                            var depth = 1
                            i += 2
                            val expr = StringBuilder()
                            while (i < src.length && depth > 0) {
                                when {
                                    src[i] == '{' -> { depth++; expr.append(src[i]); i++ }
                                    src[i] == '}' -> { depth--; if (depth > 0) expr.append(src[i]); i++ }
                                    src[i] == '\n' -> { line++; expr.append(' '); i++ }
                                    else -> { expr.append(src[i]); i++ }
                                }
                            }
                            holes += expr.toString()
                            text.append(HOLE)
                            continue
                        }
                        if (src[i] == '$' && i + 1 < src.length && (src[i + 1].isLetter() || src[i + 1] == '_')) {
                            var j = i + 1
                            while (j < src.length && (src[j].isLetterOrDigit() || src[j] == '_' || src[j] == '.')) j++
                            holes += src.substring(i + 1, j)
                            text.append(HOLE)
                            i = j
                            continue
                        }
                        text.append(src[i]); i++
                    }
                    out += Said("$where:$startLine", text.toString(), holes)
                }
                else -> i++
            }
        }
        return out
    }

    private companion object {
        val CYRILLIC = Regex("[а-яёА-ЯЁ]")

        const val HOLE = '\u0001'
    }
}
