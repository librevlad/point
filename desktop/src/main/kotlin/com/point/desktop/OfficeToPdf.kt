package com.point.desktop

import java.io.File

/**
 * Превращение офисного документа в **настоящий** PDF — со слайдами и вёрсткой, а не пересказом
 * текста (#403).
 *
 * Зачем контракт, а не вызов утилиты на месте: конвертеров на разных машинах разные, и выбор
 * между ними — решение, которое должно быть проверяемо тестом без установленного Office.
 *
 * Правило Point «не обязан всё делать сам» здесь буквально: рисовать слайды Point не умеет и не
 * будет — он спрашивает того, кто умеет, и делает это на компьютере человека, а не в чужом
 * облаке. Файл никуда не уезжает.
 */
interface OfficeToPdf {
    /** Есть ли чем конвертировать; `null` — есть. Иначе причина словами, для «недоступно». */
    fun whyUnavailable(): String?

    /** Сконвертировать в PDF рядом с исходником; `null` — не получилось. */
    fun convert(source: File): File?
}

/**
 * Каким инструментом конвертировать.
 *
 * Порядок не случайный: LibreOffice кроссплатформенный и работает без окон, поэтому пробуется
 * первым; PowerPoint — запасной для машин, где стоит Microsoft Office (у владельца именно он).
 */
enum class OfficeTool { LIBREOFFICE, POWERPOINT }

/**
 * Выбор инструмента из того, что нашлось на машине. Чистая функция — её судит тест, а поиск
 * файлов остаётся снаружи.
 */
fun chooseTool(hasLibreOffice: Boolean, hasPowerPoint: Boolean): OfficeTool? = when {
    hasLibreOffice -> OfficeTool.LIBREOFFICE
    hasPowerPoint -> OfficeTool.POWERPOINT
    else -> null
}

/** Понимает ли выбранный инструмент этот файл. */
fun convertible(name: String): Boolean =
    name.substringAfterLast('.', "").lowercase() in setOf("pptx", "ppt", "docx", "doc", "xlsx", "xls", "odp", "odt", "ods")

/**
 * Конвертер поверх того, что установлено у человека.
 *
 * Никаких докачек за спиной: если ни LibreOffice, ни PowerPoint нет, действие честно объявляется
 * недоступным — телефон не покажет кнопку, которая ничего не сделает (#316).
 */
class LocalOfficeToPdf(
    private val libreOffice: File? = findLibreOffice(),
    private val powerPointInstalled: Boolean = findPowerPoint(),
) : OfficeToPdf {

    override fun whyUnavailable(): String? =
        if (chooseTool(libreOffice != null, powerPointInstalled) == null) {
            "На компьютере нет LibreOffice или PowerPoint"
        } else {
            null
        }

    override fun convert(source: File): File? {
        if (!convertible(source.name)) return null
        val out = File(source.parentFile, source.nameWithoutExtension + ".pdf")
        val ok = when (chooseTool(libreOffice != null, powerPointInstalled)) {
            OfficeTool.LIBREOFFICE -> runLibreOffice(libreOffice!!, source)
            OfficeTool.POWERPOINT -> runPowerPoint(source, out)
            null -> false
        }
        return out.takeIf { ok && it.isFile && it.length() > 0 }
    }

    /** `--headless --convert-to pdf` кладёт результат в каталог `--outdir` с тем же именем. */
    private fun runLibreOffice(soffice: File, source: File): Boolean = runCatching {
        val process = ProcessBuilder(
            soffice.absolutePath, "--headless", "--norestore",
            "--convert-to", "pdf", "--outdir", source.parent, source.absolutePath,
        ).redirectErrorStream(true).start()
        process.waitFor()
        process.exitValue() == 0
    }.getOrDefault(false)

    /**
     * PowerPoint через COM: у него нет `--convert-to`, зато есть `SaveAs` с форматом 32 (PDF).
     * Скрипт передаётся как файл, а не строкой в аргументах: путь с кавычками и кириллицей в
     * `-Command` ломается по-разному на разных раскладках.
     */
    private fun runPowerPoint(source: File, out: File): Boolean = runCatching {
        val script = File.createTempFile("point-topdf-", ".ps1").apply {
            deleteOnExit()
            writeText(
                """
                ${'$'}ErrorActionPreference = 'Stop'
                ${'$'}app = New-Object -ComObject PowerPoint.Application
                ${'$'}pres = ${'$'}app.Presentations.Open('${source.absolutePath}', ${'$'}true, ${'$'}false, ${'$'}false)
                ${'$'}pres.SaveAs('${out.absolutePath}', 32)
                ${'$'}pres.Close()
                ${'$'}app.Quit()
                """.trimIndent(),
                Charsets.UTF_8,
            )
        }
        val process = ProcessBuilder(
            "powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", script.absolutePath,
        ).redirectErrorStream(true).start()
        process.waitFor()
        process.exitValue() == 0
    }.getOrDefault(false)
}

/** Где обычно лежит LibreOffice. Ищем по файлу, а не по PATH: у неё редко прописан PATH. */
private fun findLibreOffice(): File? = listOf(
    "C:/Program Files/LibreOffice/program/soffice.exe",
    "C:/Program Files (x86)/LibreOffice/program/soffice.exe",
    "/usr/bin/soffice",
    "/usr/local/bin/soffice",
    "/Applications/LibreOffice.app/Contents/MacOS/soffice",
).map(::File).firstOrNull { it.isFile }

private fun findPowerPoint(): Boolean = listOf(
    "C:/Program Files/Microsoft Office/root/Office16/POWERPNT.EXE",
    "C:/Program Files (x86)/Microsoft Office/root/Office16/POWERPNT.EXE",
    "C:/Program Files/Microsoft Office/Office16/POWERPNT.EXE",
).map(::File).any { it.isFile }
