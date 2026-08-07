package com.point.desktop

import java.io.File

interface OfficeToPdf {

    fun whyUnavailable(): String?

    fun convert(source: File): File?
}

enum class OfficeTool { LIBREOFFICE, POWERPOINT }

fun chooseTool(hasLibreOffice: Boolean, hasPowerPoint: Boolean): OfficeTool? = when {
    hasLibreOffice -> OfficeTool.LIBREOFFICE
    hasPowerPoint -> OfficeTool.POWERPOINT
    else -> null
}

fun convertible(name: String): Boolean =
    name.substringAfterLast('.', "").lowercase() in setOf("pptx", "ppt", "docx", "doc", "xlsx", "xls", "odp", "odt", "ods")

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

    private fun runLibreOffice(soffice: File, source: File): Boolean = runCatching {
        val process = ProcessBuilder(
            soffice.absolutePath, "--headless", "--norestore",
            "--convert-to", "pdf", "--outdir", source.parent, source.absolutePath,
        ).redirectErrorStream(true).start()
        process.waitFor()
        process.exitValue() == 0
    }.getOrDefault(false)

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
