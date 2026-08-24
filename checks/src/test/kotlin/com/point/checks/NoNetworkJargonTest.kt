package com.point.checks

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NoNetworkJargonTest {

    private val networkTalk = listOf(
        Regex("(?i)брандмауэр"),
        Regex("(?i)ф[ае][йи]рвол|firewall"),
        Regex("(?i)(?<![а-яё])порт(?:а|ы|у|ом|е|ов|ам|ами|ах)?(?![а-яё])"),
        Regex("(?i)(?<![a-z])port(?![a-z])"),
        Regex("(?i)wi-?fi|вайфай"),
        Regex("(?i)роутер|маршрутизатор"),
        Regex("(?i)локальн"),
        Regex("(?i)ip-адрес"),
        Regex("(?i)(?<![a-z])(lan|mdns)(?![a-z])"),

        Regex("(?i)(локальн|частн|публичн|домашн|гостев|эт[а-яё]+|общ[а-яё]+|одн[а-яё]+)\\s+сет"),
        Regex("(?i)сет[ьи]\\s+«?(частн|публичн|локальн)"),
        Regex("(?i)(?<![а-яё])релей?(?:я|ю|ем|е|и)?(?![а-яё])"),
    )

    private val pairingTalk = listOf(
        Regex("(?i)свяж[иу]"),
        Regex("(?i)спари"),
        Regex("(?i)сопряж"),
        Regex("(?i)пейринг"),
    )

    @Test
    fun `в словах для человека нет разговора о сетях`() {
        val guilty = productSpeech().flatMap { line ->
            networkTalk.filter { it.containsMatchIn(line.text) }
                .map { "${line.where}: «${it.find(line.text)?.value}» в «${line.text}»" }
        }

        assertTrue(
            "второй дороги между устройствами нет — значит нет и советов про её устройство:\n" +
                guilty.joinToString("\n"),
            guilty.isEmpty(),
        )
    }

    @Test
    fun `в продукте не осталось ни одного упоминания сопряжения устройств`() {

        val guilty = productSpeech().filter { line -> pairingTalk.any { it.containsMatchIn(line.text) } }

        assertTrue(guilty.joinToString("\n") { "${it.where}: «${it.text}»" }, guilty.isEmpty())
    }

    @Test
    fun `сторож правда прочитал продукт, а не пустоту`() {

        assertTrue("исходников не нашлось: ${repo.absolutePath}", sources().size > 100)
        assertTrue("человеческих строк не нашлось", productSpeech().size > 300)
        assertTrue(
            "экран компьютера не прочитан — а неправда про круг жила именно там",
            sources().any { "desktop" in it.path.replace('\\', '/') },
        )
    }

    @Test
    fun `сторож видит запретное слово в строке и не видит его в комментарии`() {
        val sample = """
            package com.point.sample
            // Слушающий сокет на Windows вызывает окно брандмауэра — поэтому его и нет.
            class Sample {
                fun ok() = "Компьютер не отвечает. Проверьте, что «Point для ПК» на нём запущен."
                fun bad() = "Откройте порт в брандмауэре Windows"
            }
        """.trimIndent()

        val said = saidIn("sample.kt", sample)

        assertEquals(
            listOf(
                "Компьютер не отвечает. Проверьте, что «Point для ПК» на нём запущен.",
                "Откройте порт в брандмауэре Windows",
            ),
            said.map { it.text },
        )
        assertEquals(
            listOf("Откройте порт в брандмауэре Windows"),
            said.filter { line -> networkTalk.any { it.containsMatchIn(line.text) } }.map { it.text },
        )
    }

    @Test
    fun `сторож не путает похожие слова с запретными`() {

        val innocent = listOf(
            "Портал ждёт объект",
            "заголовок документа шапкой сетки не становится",
            "Сеточная разметка листа",
            "частота повторов",
            "Портрет · размытие фона",
        )

        assertTrue(
            innocent.filter { text -> networkTalk.any { it.containsMatchIn(text) } }.joinToString("\n"),
            innocent.none { text -> networkTalk.any { it.containsMatchIn(text) } },
        )
    }

    private data class Said(val where: String, val text: String)


    private fun sources(): List<File> = listOf("app", "core", "data", "executors", "desktop")
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
                            while (i < src.length && depth > 0) {
                                when {
                                    src[i] == '{' -> { depth++; i++ }
                                    src[i] == '}' -> { depth--; i++ }
                                    src[i] == '\n' -> { line++; i++ }
                                    else -> i++
                                }
                            }
                            text.append(HOLE)
                            continue
                        }
                        if (src[i] == '$' && i + 1 < src.length && (src[i + 1].isLetter() || src[i + 1] == '_')) {
                            var j = i + 1
                            while (j < src.length && (src[j].isLetterOrDigit() || src[j] == '_' || src[j] == '.')) j++
                            text.append(HOLE)
                            i = j
                            continue
                        }
                        text.append(src[i]); i++
                    }
                    out += Said("$where:$startLine", text.toString())
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
