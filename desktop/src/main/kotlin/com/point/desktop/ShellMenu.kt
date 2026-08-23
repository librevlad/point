package com.point.desktop

import java.io.File

interface ShellMenu {
    fun registeredCommand(): String?

    /**
     * Стоит ли пункт в меню файла (#1082).
     *
     * Пункт в Проводнике живёт своим ключом, а не подключом команды: ключ без команды — это
     * мёртвый пункт, а не снятый, и спрашивать про снятие надо про ключ. `null` — прочитать
     * не удалось; «не прочиталось» — это не «не стоит».
     */
    fun present(): Boolean?

    /**
     * Записывает пункт и отвечает правдой: `true` — команда действительно читается из реестра.
     * Не встало целиком — не остаётся ничего: половина записи была бы тем самым мёртвым
     * пунктом, с которого началась карточка (#1082).
     *
     * `null` — записали, а прочитать обратно не вышло: реестр молчит. Это не «не встало», и
     * откатывать по такому ответу нечего — снести исправную запись из-за непрочитанного
     * ответа было бы тем же сбоем чтения, выданным за сбой записи.
     */
    fun register(command: String, title: String): Boolean?

    /**
     * Снимает пункт и отвечает по эффекту: `true` — пункта в реестре больше нет; `null` —
     * реестр не прочитался, и снятость из этого не следует (#1082).
     */
    fun unregister(): Boolean?
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
 * Стоит ли ярлык там, где его ждут (#1082): `true` — читается и ведёт куда надо, `false` —
 * знание, что не стоит, `null` — прочитать не удалось.
 *
 * Куда ведёт ярлык, спрашивает Windows через COM, и её ответ может не прийти. Лежащий на диске,
 * но не прочитанный ярлык — не «не встал»: сбой чтения не есть сбой записи. Правило одно и на
 * чтение при показе экрана, и на ответ самой записи: разъехавшись по путям, оно и дало ту самую
 * неправду — «Не удалось включить» поверх исправной установки.
 */
fun linkHolds(present: Boolean, target: String?, wanted: String?): Boolean? = when {
    wanted == null -> false
    target == wanted -> true
    present && target == null -> null
    else -> false
}

/**
 * Два ответа вместе (#1082): «не вышло» перевешивает — оно знание; «не прочиталось» отвечается
 * только когда провалов нет. Пункт меню и ярлык «Отправить» ставятся одним движением руки, и
 * человеку отвечают об этом движении, а не о каждой записи отдельно.
 */
fun bothHold(first: Boolean?, second: Boolean?): Boolean? = when {
    first == false || second == false -> false
    first == null || second == null -> null
    else -> true
}

/**
 * Стоит ли меню файла Windows в этом положении выключателя на деле (#1082): включено — пункт
 * «Открыть в Point» ведёт в эту установку и ярлык «Отправить → Point» тоже; выключено — не
 * осталось ни того, ни другого. Спрашивается у реестра и папки «Отправить», а не у памяти
 * экрана: после перезапуска переключатель говорит то, что есть, а не то, что когда-то нажали.
 *
 * `null` — прочитать не удалось: знания нет, и выдавать нечитаемый реестр за пустой нельзя.
 * Правило одно на оба положения выключателя: не прочиталось — не ответ ни «стоит», ни «не
 * стоит». Куда ведёт ярлык, спрашивает Windows через COM, и её ответ может не прийти — лежащий
 * на диске, но не прочитанный ярлык такой же непрочитанный, как нечитаемый реестр.
 *
 * Снятость спрашивается про сам пункт и сам ярлык, а не про их содержимое: ключ без команды и
 * ярлык, который не прочитался, — это оставшиеся пункты, а не снятые.
 *
 * Ключ пункта без команды остаётся ответом «не стоит», а не «не знаю»: реестр только что
 * прочитался (`menuPresent` не `null`), значит команды в нём правда нет — это тот самый мёртвый
 * пункт, с которого началась карточка, и прятать его за «Проверяется» нельзя.
 */
fun rightClickHolds(
    on: Boolean,
    exe: File?,
    menuPresent: Boolean?,
    command: String?,
    linkPresent: Boolean,
    linkTarget: String?,
): Boolean? = when {
    menuPresent == null -> null
    !on -> !menuPresent && !linkPresent

    // Запуск из исходников: установленного Point нет, пункта не будет, и врать про него нечего.
    exe == null -> false
    else -> bothHold(command == shellCommandFor(exe), linkHolds(linkPresent, linkTarget, exe.absolutePath))
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

    override fun register(command: String, title: String): Boolean? {
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
            run(listOf("reg", "import", file.absolutePath))

            // Сбой больше не глотается: успех — это команда, читаемая обратно из реестра, а не
            // код `reg import` (#1082). Но и отрицательный ответ ещё не «не встало»: `reg query`
            // молчит и когда реестр не читается вовсе. Отличает одно от другого то же
            // контрольное чтение, что и в `present()`.
            when {
                registeredCommand() == command -> true
                present() == null -> null
                else -> false
            }
        } finally {
            file.delete()
        }

        // Ответ «не встало» без отката оставлял в реестре то, что успело записаться, — название
        // без команды, то есть мёртвый пункт. Не встало целиком — в меню не остаётся ничего.
        // Откат идёт только по знанию: молчание реестра им не является, и сносить по нему
        // исправную запись нельзя.
        if (stood == false) unregister()
        return stood
    }

    override fun present(): Boolean? {
        if (!windows) return false
        val (code, _) = run(listOf("reg", "query", MENU_KEY))
        if (code == 0) return true

        // `reg query` отвечает ненулевым кодом и когда ключа правда нет, и когда реестр не
        // читается вовсе — `reg` не запустился, доступа нет. Одно от другого отличает только
        // контрольное чтение заведомо существующего родителя: читается он — реестр жив, и
        // ключа действительно нет; не читается — знания нет, и молчание не выдаётся за
        // отсутствие пункта (#1082).
        val (parent, _) = run(listOf("reg", "query", CLASSES_KEY))
        return if (parent == 0) false else null
    }

    override fun unregister(): Boolean? {
        if (!windows) return true
        run(listOf("reg", "delete", MENU_KEY, "/f"))

        // Код возврата `reg delete` не ответ: ключа могло не быть и до того. Не ответ и
        // отсутствие команды: пункт в Проводнике живёт ключом меню, и ключ без команды —
        // мёртвый пункт, а не снятый. Ответ — прочитанный обратно реестр; не прочитался —
        // ответа нет ни «снято», ни «осталось» (#1082).
        return present()?.let { !it }
    }

    private companion object {

        const val CLASSES_KEY = """HKCU\Software\Classes"""
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

    /** Куда сейчас указывает ярлык «Отправить → Point», или `null` — его нет либо не прочёлся. */
    fun target(): String?

    /**
     * Лежит ли ярлык «Отправить → Point» на диске (#1082).
     *
     * Куда он ведёт, спрашивает Windows, и её ответ может не прийти; сам файл виден и без
     * этого вопроса. Иначе непрочитанный ярлык выглядел бы снятым — та же неправда, что
     * ключ меню без команды.
     */
    fun present(): Boolean

    /**
     * Кладёт ярлык и отвечает по эффекту: `true` — ярлык читается обратно и ведёт в `exe`;
     * `null` — ярлык лежит, а Windows про его цель промолчала (#1082).
     */
    fun register(exe: File): Boolean?

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

    override fun present(): Boolean = folder?.let(::sendToShortcut)?.isFile == true

    override fun register(exe: File): Boolean? {
        val place = folder ?: return false
        place.mkdirs()
        run(powershell(sendToScript(exe, sendToShortcut(place))))

        // Успех — не код PowerShell, а ярлык, прочитанный обратно: он есть и ведёт в Point.
        // Правило «не прочиталось — не ответ» здесь то же самое и то же по коду, что и при
        // чтении для экрана: молчание Windows про цель ярлыка уходило человеку словами
        // «Не удалось включить» — сбоем записи, которого не было (#1082).
        return linkHolds(present(), target(), exe.absolutePath)
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
