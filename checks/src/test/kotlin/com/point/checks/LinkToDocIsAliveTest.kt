package com.point.checks

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ссылка на документ ведёт на существующий файл (#1234).
 *
 * Пятнадцать мест в репозитории отправляли человека читать документы, которых нет ни в дереве,
 * ни в истории: карту кадров корпуса, реестр решений, замер зрячих моделей, контракт компьютера,
 * спецификацию сервера, устройство архитектуры с публичного лендинга. Ссылка-обещание хуже
 * молчания: человек идёт по адресу, не находит ничего и дальше либо восстанавливает основания
 * заново, либо принимает решение без них. Хуже всего было в счёте корпуса — там несуществующий
 * адрес печатался владельцу в отчёт при каждом замере.
 *
 * Сторож нарочно дешёвый: он не читает документы и не судит о содержании, а только проверяет,
 * что адрес ведёт в файл, который есть. Больше сюда не смотрит никто — релизный гейт исключает
 * `docs/**` и `**/*.md` из своего скана, — и без сторожа список мёртвых адресов отрастал бы снова.
 *
 * Живёт в `:checks` (#1293): битый адрес заводится где угодно — в шаблоне PR, в таблице корпуса,
 * на лендинге, в коде ядра, в сервере на Python, — и модуля, который собирал бы всё это разом,
 * в проекте нет.
 */
class LinkToDocIsAliveTest {

    @Test
    fun `каждая ссылка на документ ведёт на существующий файл`() {
        val dead = links().filterNot(::alive)

        assertTrue(
            "ссылка обещает основания, а по адресу пусто — либо документ пишется, либо ссылка " +
                "правится на живой адрес; обещание оставлять нельзя:\n" +
                dead.joinToString("\n") { "${it.where}: ${it.target}" },
            dead.isEmpty(),
        )
    }

    @Test
    fun `сторож правда прочитал проект, а не одни исходники`() {
        val read = scanned().map { it.toRelativeString(repo).replace('\\', '/') }.toSet()

        assertTrue("файлов проекта не нашлось: ${repo.absolutePath}", read.size > 500)
        assertTrue("живых ссылок не нашлось вовсе — значит сторож смотрит в пустоту", links().size > 10)
        listOf(
            ".github/pull_request_template.md",
            "tools/corpus/frames.tsv",
            "site/index.html",
            "relay/point_server/db.py",
            "CLAUDE.md",
        ).forEach {
            assertTrue("не прочитан $it — а мёртвые адреса жили и там тоже", it in read)
        }
    }

    @Test
    fun `сторож видит мёртвый адрес и не трогает живой`() {
        // Адреса собираются из кусков нарочно: целиком написанный мёртвый адрес сторож нашёл бы
        // в исходнике самого сторожа и уронил бы сам себя.
        val alive = "docs/" + "CONSTITUTION.md"
        val dead = "docs/" + "NO-SUCH-DOCUMENT.md"

        val found = linksIn("образец.md", "разбор в $alive, а решение в $dead (#262)")

        assertEquals("оба адреса найдены", listOf(alive, dead), found.map { it.target })
        assertEquals("мёртвым назван ровно один", listOf(dead), found.filterNot(::alive).map { it.target })
    }

    @Test
    fun `сторож не путает адрес с образцом пути и с чужим словом`() {
        val innocent = "исключает docs/** и **/*.md; docs/ пуст; смотри documents/ARCHITECTURE.md"

        assertEquals("ни одного адреса тут нет", emptyList<String>(), linksIn("образец.md", innocent).map { it.target })
    }

    private data class Link(val where: String, val target: String)

    private fun alive(link: Link): Boolean = File(repo, link.target).isFile

    /**
     * Адрес ищется в тексте, а не в разметке: одна и та же ссылка живёт комментарием Kotlin,
     * строкой tsv, HTML-атрибутом лендинга и строкой отчёта, и разбирать четыре синтаксиса
     * значило бы завести четыре дырки.
     */
    private fun linksIn(where: String, text: String): List<Link> =
        ADDRESS.findAll(text).map { Link(where, it.value) }.toList()

    private fun links(): List<Link> = scanned()
        .flatMap { linksIn(it.toRelativeString(repo).replace('\\', '/'), it.readText()) }

    /**
     * Читается всё, кроме заведомо двоичного: список того, что читать НАДО, отставал бы от
     * репозитория молча — новый тип файла просто не попадал бы под сторожа.
     */
    private fun scanned(): List<File> = repo.walkTopDown()
        .onEnter { it.name !in SKIPPED }
        .filter { it.isFile && it.extension.lowercase() !in BINARY }
        .toList()

    private companion object {

        val ADDRESS = Regex("docs/[A-Za-z0-9_.\\-/]+\\.md")

        /** Своя сборка, чужая копия репозитория рядом и служебные каталоги проектом не являются. */
        val SKIPPED = setOf(
            "build", ".git", ".gradle", ".idea", ".kotlin", "worktrees", "node_modules",
            ".venv", "__pycache__",
        )

        val BINARY = setOf(
            "png", "jpg", "jpeg", "gif", "webp", "ico", "pdf", "zip", "jar", "apk", "aab", "so",
            "ttf", "otf", "woff", "woff2", "wav", "mp3", "mp4", "ogg", "onnx", "traineddata",
            "keystore", "jks", "bin", "class", "db",
        )
    }
}
