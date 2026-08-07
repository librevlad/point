package com.point.desktop

import java.io.File

interface ShellMenu {
    fun registeredCommand(): String?
    fun register(command: String, title: String)
    fun unregister()
}

fun shellCommandFor(exe: File): String = "\"${exe.absolutePath}\" \"%1\""

fun shellMenuNeedsUpdate(current: String?, wanted: String): Boolean = current != wanted

fun installedExecutable(command: String?): File? {
    val path = command?.takeIf { it.isNotBlank() } ?: return null
    val exe = File(path)
    return exe.takeIf { it.isFile && it.extension.equals("exe", ignoreCase = true) }
}

class RegistryShellMenu(
    private val run: (List<String>) -> Pair<Int, String> = ::runProcess,
) : ShellMenu {

    private val windows = System.getProperty("os.name").orEmpty().lowercase().contains("win")

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
