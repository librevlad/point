package com.point

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Сторож против жаргона (#541): в словах, которые Point говорит человеку, не бывает наших слов.
 *
 * Список живых нарушений однажды закончится — правило останется. Поэтому здесь не перечень строк,
 * а проверка по исходникам: имя внутренней сущности, имя библиотеки, сырой тип файла и кусок
 * чужого ответа в человеческой строке роняют сборку, где бы их завтра ни написали.
 *
 * **Как отличается текст для человека от кода и комментариев.** Комментарии вырезаются разбором
 * ([saidIn] — маленький лексер, который знает про `//`, `/* */`, строки и символы), а из
 * оставшегося берутся **строковые литералы с кириллицей**. Кириллица здесь и есть признак «это
 * сказано человеку»: продукт говорит по-русски, а имена в коде, ключи метаданных, типы файлов и
 * запросы к сервисам пишутся латиницей. Значит честный комментарий про `OpenCV` — а он в коде
 * есть и нужен, там объяснено, почему реализатор гейтится загрузкой нативной библиотеки, —
 * сторожа не будит; строка с тем же словом будит.
 *
 * Ровно это разделение и делает сторожа ценным. Сторож, падающий на честном комментарии, учит
 * обходить себя: слова вычёркивают из объяснений, а не из отказов, — и через месяц он охраняет
 * пустоту.
 *
 * Смотрит на телефонный продукт (`:app`, `:core`, `:data`, `:executors`) и только на `src/main`:
 * тесты и отладочные экраны говорят с разработчиком, а не с человеком.
 */
class NoJargonInProductTest {

    // --- что именно нельзя сказать человеку ---

    /**
     * Имена наших собственных деталей. Человек не выбирал ни одну из них и не может ни на что
     * повлиять, узнав их название, — а разработчик всё равно пойдёт смотреть код.
     */
    private val internals = listOf(
        "Realizer", "Capability", "Resolver", "Enricher", "Recognizer", "Rasterizer",
        "ObjectStore", "LlmClient", "BubblePolicy", "ScratchRef", "PointObject", "ActionResult",
        "реализатор", "реализаци", "капабилити", "резолвер", "энричер",
    )

    /** Имена библиотек: чужой код, о котором человек не просил и который завтра сменится. */
    private val libraries = listOf(
        "OpenCV", "Tesseract", "ZXing", "MLKit", "ML Kit", "PDFBox", "PdfBox", "Apache POI",
        "OkHttp", "Retrofit", "Hilt", "Dagger", "Robolectric", "Bitmap", "Canvas",
    )

    /** Сырой тип файла: `application/vnd.…` — обрывок машинного словаря, а не имя вещи. */
    private val rawMime = Regex("""(?i)\b(application|image|audio|video|text|multipart)/[a-z0-9][a-z0-9.+\-]*""")

    /** Подстановка типа файла прямо в строку — тот же сырой тип, только собранный на лету. */
    private val mimeHole = Regex("""(?i)\b(mime|mimeType|contentType)\b""")

    /**
     * Кусок чужого ответа: тело запроса, обрезанное по символам.
     *
     * Признак — **и** имя перевозки (`body`, `json`, …), **и** обрезка по длине: вместе они
     * значат «показываем сырьё, сколько влезет». Фраза, которую сервис сказал словами, под это
     * не подходит и подходить не должна — её человеку показать полезно (`error_message` у
     * читателя страниц, `Сервис ответил: …` на экране ключей).
     */
    private val payloadHole = Regex("""\b(body|json|payload|raw|responseBody|html)\b""")

    /** Формат лога «Модуль: сообщение», случайно показанный человеку: «PDF: неподдерживаемый вход». */
    private val logShape = Regex("""^[A-Za-z][A-Za-z0-9 .+_\-]{0,20}:\s""")

    // --- сторож ---

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

    // --- сторож обязан что-то охранять ---

    @Test
    fun `сторож правда прочитал продукт, а не пустоту`() {
        // Сломанный путь до исходников сделал бы все проверки выше вечнозелёными — самая дорогая
        // из возможных поломок: она не видна ни в одном прогоне.
        val files = sources()
        val said = productSpeech()

        assertTrue("исходников не нашлось: ${root.absolutePath}", files.size > 100)
        assertTrue("человеческих строк не нашлось", said.size > 300)
    }

    // --- сторож обязан отличать сказанное от объяснённого ---

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
        // Мутация, вшитая в тест: каждое правило проверяется на строке, которая его нарушает, —
        // иначе «зелено» перестало бы что-либо значить в тот день, когда правило сломается.
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

    // --- чтение исходников ---

    /** Строка, сказанная человеку: где написана, что написано и что подставляется на лету. */
    private data class Said(val where: String, val text: String, val holes: List<String>)

    private val root: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private fun sources(): List<File> = listOf("app", "core", "data", "executors")
        .map { File(root, it) }
        .flatMap { module ->
            module.walkTopDown()
                .onEnter { it.name != "build" }
                .filter { it.isFile && it.extension == "kt" }
                .filter { "/src/main/" in it.path.replace('\\', '/') }
                .toList()
        }

    /** Всё, что продукт говорит человеку: строковые литералы с кириллицей, без комментариев. */
    private fun productSpeech(): List<Said> = sources()
        .flatMap { saidIn(it.toRelativeString(root), it.readText()) }
        .filter { CYRILLIC.containsMatchIn(it.text) }

    /**
     * Слово целиком — но по-разному для двух языков.
     *
     * Латинское имя ищется с границами: «PDF» внутри «PdfBox» не в счёт, а «OpenCV-скана» — в
     * счёт. Русское же пишется корнем («реализаци»), потому что склоняется: искать его с границей
     * значило бы завести список падежей и однажды его не дописать.
     */
    private fun String.containsWord(word: String): Boolean =
        if (word.first() in 'A'..'z') {
            Regex("(?i)(?<![A-Za-z])" + Regex.escape(word) + "(?![A-Za-z])").containsMatchIn(this)
        } else {
            contains(word, ignoreCase = true)
        }

    /**
     * Строковые литералы файла — комментарии выброшены, подстановки вынуты отдельно.
     *
     * Маленький лексер вместо регулярного выражения: `"` живёт и в комментарии, и в символьном
     * литерале, и в самой строке, и отличить их можно только чтением слева направо. Регулярка
     * ошибалась бы там, где сторожу верят.
     */
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

        /** Метка подстановки внутри строки: само подставляемое выражение словом не считается. */
        const val HOLE = '\u0001'
    }
}
