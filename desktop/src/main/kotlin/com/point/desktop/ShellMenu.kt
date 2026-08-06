package com.point.desktop

import java.io.File

/**
 * «Открыть в Point» по правой кнопке (#252).
 *
 * Единственная работа, которая держит обещание первоисточника — «любой объект сначала открываю в
 * Point». Пока её нет, у компьютера один вход: открыть окно и перетащить туда файл, то есть Point
 * это программа, к которой надо прийти, а не то, что под рукой.
 *
 * Половина была сделана давно: файл, названный в командной строке, уходит уже открытому Point
 * (`SendToRunning`). Не хватало второй — того, чтобы система про этот пункт знала.
 *
 * **Записывается для текущего пользователя.** Прав администратора не нужно, чужим пользователям
 * машины пункт не навязывается.
 */
interface ShellMenu {
    fun registeredCommand(): String?
    fun register(command: String, title: String)
    fun unregister()
}

/**
 * Что должно быть записано в системе, чтобы правая кнопка открывала **этот** Point.
 *
 * Кавычки вокруг обоих кусков обязательны: и путь установки, и путь к файлу человека почти всегда
 * содержат пробелы («Program Files», «Мои документы»), а без кавычек система разрежет их по
 * пробелу и передаст Point половину имени.
 */
fun shellCommandFor(exe: File): String = "\"${exe.absolutePath}\" \"%1\""

/**
 * Надо ли переписать запись в системе.
 *
 * Не только «её нет»: Point могли переустановить в другую папку, и тогда пункт меню ведёт в
 * пустоту — молча, потому что система просто не найдёт файл. Такое чинится само, без единого
 * вопроса человеку.
 *
 * `null` в [current] — записи нет вовсе.
 */
fun shellMenuNeedsUpdate(current: String?, wanted: String): Boolean = current != wanted

/**
 * Где лежит запускаемый Point — или `null`, если мы работаем не из установленной сборки.
 *
 * Из исходников (`./gradlew :desktop:run`) регистрировать нечего: путь ведёт в каталог сборки,
 * который завтра исчезнет, и пункт меню останется указывать в пустоту. Это ровно тот мусор, из-за
 * которого программу запоминают плохо.
 */
fun installedExecutable(command: String?): File? {
    val path = command?.takeIf { it.isNotBlank() } ?: return null
    val exe = File(path)
    return exe.takeIf { it.isFile && it.extension.equals("exe", ignoreCase = true) }
}

/**
 * Запись в реестре Windows через `reg.exe` (#252).
 *
 * Штатной утилитой, а не чужой библиотекой: ради четырёх строк тянуть зависимость в образ незачем,
 * а `reg.exe` есть в любой Windows и делает ровно это.
 *
 * На других системах молчит: пункта контекстного меню там нет, и делать вид, что есть, Point не
 * станет.
 */
class RegistryShellMenu(
    private val run: (List<String>) -> Pair<Int, String> = ::runProcess,
) : ShellMenu {

    private val windows = System.getProperty("os.name").orEmpty().lowercase().contains("win")

    override fun registeredCommand(): String? {
        if (!windows) return null
        val (code, out) = run(listOf("reg", "query", COMMAND_KEY, "/ve"))
        if (code != 0) return null
        // Вывод `reg query` — таблица; нас интересует значение по умолчанию, оно в последней
        // колонке строки с REG_SZ.
        return out.lineSequence()
            .firstOrNull { it.contains("REG_SZ") }
            ?.substringAfter("REG_SZ")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    override fun register(command: String, title: String) {
        if (!windows) return
        run(listOf("reg", "add", MENU_KEY, "/ve", "/d", title, "/f"))
        run(listOf("reg", "add", COMMAND_KEY, "/ve", "/d", command, "/f"))
    }

    override fun unregister() {
        if (!windows) return
        run(listOf("reg", "delete", MENU_KEY, "/f"))
    }

    private companion object {
        /** `*` — «любой файл»: обещание первоисточника звучит именно так. */
        const val MENU_KEY = """HKCU\Software\Classes\*\shell\Point"""
        const val COMMAND_KEY = """HKCU\Software\Classes\*\shell\Point\command"""
    }
}

private fun runProcess(command: List<String>): Pair<Int, String> = runCatching {
    val process = ProcessBuilder(command).redirectErrorStream(true).start()
    val text = process.inputStream.bufferedReader().use { it.readText() }
    process.waitFor()
    process.exitValue() to text
}.getOrDefault(-1 to "")

