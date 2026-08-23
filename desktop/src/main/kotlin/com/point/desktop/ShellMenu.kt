package com.point.desktop

import java.io.File

interface ShellMenu {
    fun registeredCommand(): String?

    /**
     * Записывает пункт и отвечает правдой: `true` — команда действительно читается из реестра.
     * Не встало целиком — не остаётся ничего: половина записи была бы тем самым мёртвым
     * пунктом, с которого началась карточка (#1082).
     */
    fun register(command: String, title: String): Boolean

    /** Снимает пункт и отвечает по эффекту: `true` — команды в реестре больше нет (#1082). */
    fun unregister(): Boolean
}

/** Название пункта в меню файла — одно и при старте, и из настроек. */
const val SHELL_MENU_TITLE = "Открыть в Point"

fun shellCommandFor(exe: File): String = "\"${exe.absolutePath}\" \"%1\""

/**
 * Текст .reg-файла с пунктом меню и его командой (#1082).
 *
 * Запись идёт `reg import`-ом файла, а не `reg add`-ом аргумента: команда пункта содержит
 * внутренние кавычки — `"C:\…\Point.exe" "%1"`, — а Java на Windows не экранирует кавычки
 * внутри аргументов процесса. `reg add` получал битую строку, отвечал `Invalid syntax`, и пункт
 * оставался без команды — мёртвым. В файле команду искажать некому.
 */
fun shellMenuRegFile(command: String, title: String): String = listOf(
    "Windows Registry Editor Version 5.00",
    "",
    "[HKEY_CURRENT_USER\\Software\\Classes\\*\\shell\\Point]",
    "@=\"${regValue(title)}\"",
    "",
    "[HKEY_CURRENT_USER\\Software\\Classes\\*\\shell\\Point\\command]",
    "@=\"${regValue(command)}\"",
    "",
).joinToString("\r\n")

/** Строка в .reg-синтаксисе: обратная косая и кавычка экранируются, больше ничего. */
private fun regValue(text: String): String = text.replace("\\", "\\\\").replace("\"", "\\\"")

fun shellMenuNeedsUpdate(current: String?, wanted: String): Boolean = current != wanted

/**
 * Стоит ли меню файла Windows в этом положении выключателя на деле (#1082): включено — пункт
 * «Открыть в Point» ведёт в эту установку и ярлык «Отправить → Point» тоже; выключено — не
 * осталось ни того, ни другого. Спрашивается у реестра и папки «Отправить», а не у памяти
 * экрана: после перезапуска переключатель говорит то, что есть, а не то, что когда-то нажали.
 */
fun rightClickHolds(on: Boolean, exe: File?, command: String?, link: String?): Boolean = when {
    on -> exe != null && command == shellCommandFor(exe) && link == exe.absolutePath
    else -> command == null && link == null
}

fun installedExecutable(command: String?): File? {
    val path = command?.takeIf { it.isNotBlank() } ?: return null
    val exe = File(path)
    return exe.takeIf { it.isFile && it.extension.equals("exe", ignoreCase = true) }
}

class RegistryShellMenu(
    private val run: (List<String>) -> Pair<Int, String> = ::runProcess,
    private val windows: Boolean = System.getProperty("os.name").orEmpty().lowercase().contains("win"),
) : ShellMenu {

    override fun registeredCommand(): String? {
        if (!windows) return null
        val (code, out) = run(listOf("reg", "query", COMMAND_KEY, "/ve"))
        if (code != 0) return null

        return out.lineSequence()
            .firstOrNull { it.contains("REG_SZ") }
            ?.substringAfter("REG_SZ")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    override fun register(command: String, title: String): Boolean {
        if (!windows) return false
        val file = runCatching {
            File.createTempFile("point-shell-menu", ".reg").apply {
                // UTF-16LE с BOM — родная кодировка .reg-файла: название пункта русское.
                writeBytes(
                    byteArrayOf(0xFF.toByte(), 0xFE.toByte()) +
                        shellMenuRegFile(command, title).toByteArray(Charsets.UTF_16LE),
                )
            }
        }.getOrNull() ?: return false

        val stood = try {
            val (code, _) = run(listOf("reg", "import", file.absolutePath))

            // Сбой больше не глотается: успех — это команда, читаемая обратно из реестра (#1082).
            code == 0 && registeredCommand() == command
        } finally {
            file.delete()
        }

        // Ответ «не встало» без отката оставлял в реестре то, что успело записаться, — название
        // без команды, то есть мёртвый пункт. Не встало целиком — в меню не остаётся ничего.
        if (!stood) unregister()
        return stood
    }

    override fun unregister(): Boolean {
        if (!windows) return true
        run(listOf("reg", "delete", MENU_KEY, "/f"))

        // Код возврата `reg delete` не ответ: ключа могло не быть и до того. Ответ — реестр,
        // прочитанный обратно: команды больше нет.
        return registeredCommand() == null
    }

    private companion object {

        const val MENU_KEY = """HKCU\Software\Classes\*\shell\Point"""
        const val COMMAND_KEY = """HKCU\Software\Classes\*\shell\Point\command"""
    }
}

/**
 * Пункт «Отправить → Point» в Проводнике (#255, решение владельца 10.08.2026).
 *
 * Исходная формулировка карточки — «выделил текст, правая кнопка, Point» — Windows не
 * позволяет: меню на выделенном тексте принадлежит браузеру или Word, а не системе. Поэтому
 * вход другой и выполнимый: привычное «Отправить», работающее и для нескольких файлов сразу.
 *
 * Меню «Отправить» — не реестр, а папка с ярлыками, поэтому запись здесь не `reg`, а ярлык;
 * снимается тем же выключателем правой кнопки — оба пункта человек видит одним движением руки.
 */
interface SendToMenu {

    /** Куда сейчас указывает ярлык «Отправить → Point», или `null` — его нет. */
    fun target(): String?

    /** Кладёт ярлык и отвечает по эффекту: `true` — ярлык читается обратно и ведёт в `exe` (#1082). */
    fun register(exe: File): Boolean

    /** Снимает ярлык и отвечает по эффекту: `true` — ярлыка больше нет (#1082). */
    fun unregister(): Boolean
}

fun sendToFolder(appData: String? = System.getenv("APPDATA")): File? =
    appData?.takeIf { it.isNotBlank() }?.let { File(it, "Microsoft/Windows/SendTo") }

fun sendToShortcut(folder: File): File = File(folder, "Point.lnk")

/** Скрипт создания ярлыка: тот же способ, каким Windows делает их сама. */
fun sendToScript(exe: File, link: File): String =
    "\$s = (New-Object -ComObject WScript.Shell).CreateShortcut('${link.absolutePath}'); " +
        "\$s.TargetPath = '${exe.absolutePath}'; " +
        "\$s.WorkingDirectory = '${exe.parentFile?.absolutePath.orEmpty()}'; " +
        "\$s.Save()"

private fun sendToReadScript(link: File): String =
    "(New-Object -ComObject WScript.Shell).CreateShortcut('${link.absolutePath}').TargetPath"

class ShortcutSendToMenu(
    private val folder: File? = sendToFolder(),
    private val run: (List<String>) -> Pair<Int, String> = ::runProcess,
) : SendToMenu {

    override fun target(): String? {
        val link = folder?.let(::sendToShortcut)?.takeIf { it.isFile } ?: return null
        val (code, out) = run(powershell(sendToReadScript(link)))
        return if (code != 0) null else out.trim().takeIf { it.isNotEmpty() }
    }

    override fun register(exe: File): Boolean {
        val place = folder ?: return false
        place.mkdirs()
        run(powershell(sendToScript(exe, sendToShortcut(place))))

        // Успех — не код PowerShell, а ярлык, прочитанный обратно: он есть и ведёт в Point.
        return target() == exe.absolutePath
    }

    override fun unregister(): Boolean {
        val link = folder?.let(::sendToShortcut) ?: return true
        link.delete()
        return !link.exists()
    }

    private fun powershell(script: String) =
        listOf("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", script)
}

private fun runProcess(command: List<String>): Pair<Int, String> = runCatching {
    val process = ProcessBuilder(command).redirectErrorStream(true).start()
    val text = process.inputStream.bufferedReader().use { it.readText() }
    process.waitFor()
    process.exitValue() to text
}.getOrDefault(-1 to "")
