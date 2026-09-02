package com.point.checks

import java.io.File

/**
 * Имена способностей, прочитанные из исходников текстом (#1379).
 *
 * Модуля, который собирал бы телефон и компьютер разом, в проекте нет: `:executors` —
 * android-библиотека, `:desktop` — обычный JVM. Поэтому оба списка читаются текстом, как и
 * остальные проверки этого модуля.
 *
 * Ключ сверки — id способности, а не имя класса. Телефон носит тонкие двери Hilt
 * (`XCapabilityOnPhone`), компьютер зовёт ту же способность её собственным классом, и по
 * именам классов одна работа выглядела бы двумя.
 */
object CapabilityIds {

    /** Тело каждого объявленного в файле класса — по счёту фигурных скобок. */
    fun classBodies(text: String): List<Pair<String, String>> =
        Regex("""\bclass\s+(\w+)""").findAll(text).mapNotNull { m ->
            val open = text.indexOf('{', m.range.last)
            if (open < 0) return@mapNotNull null
            var depth = 0
            var i = open
            while (i < text.length) {
                if (text[i] == '{') depth++
                if (text[i] == '}') {
                    depth--
                    if (depth == 0) break
                }
                i++
            }
            m.groupValues[1] to text.substring(open, minOf(i + 1, text.length))
        }.toList()

    /** Имена из общего реестра: `val OCR = CapabilityId("ocr")`. */
    fun registry(root: File): Map<String, String> =
        Regex("""val (\w+) = CapabilityId\("([^"]+)"\)""")
            .findAll(File(root, REGISTRY).readText())
            .associate { it.groupValues[1] to it.groupValues[2] }

    /**
     * Карта «класс способности → её id».
     *
     * Способность называет себя четырьмя способами, и все четыре в проекте живые: литералом,
     * своим `ID`, именем из общего реестра и через дверь, которая берёт id у обёрнутой
     * способности. Разбор, знающий не все, молча теряет способность из счёта — а тогда сторож
     * пропустил бы ровно то, ради чего заведён.
     */
    fun map(root: File, roots: List<String>): Map<String, String> {
        val known = registry(root)
        val direct = mutableMapOf<String, String>()
        val doors = mutableMapOf<String, String>()

        roots.map { File(root, it) }.filter { it.isDirectory }
            .flatMap { it.walkTopDown().filter { f -> f.extension == "kt" }.toList() }
            .forEach { file ->
                val text = file.readText()
                for ((name, body) in classBodies(text)) {
                    if (!name.contains("Capability")) continue
                    val said = Regex("""override val id\s*=\s*(.+)""").find(body)?.groupValues?.get(1)?.trim()
                    val id = when {
                        said == null -> null
                        said.startsWith("CapabilityId(") ->
                            Regex("""CapabilityId\("([^"]+)"\)""").find(said)?.groupValues?.get(1)
                        said == "ID" -> ownId(body, known)
                        else -> known[said.substringAfterLast('.')]
                    }
                    if (id != null) {
                        direct[name] = id
                    } else if (body.contains("override val id get() = inner.id")) {
                        Regex("""private val inner = (?:[\w.]+\.)?(\w+)\(""").find(body)
                            ?.groupValues?.get(1)?.let { doors[name] = it }
                    }
                }
            }

        return direct.keys.plus(doors.keys).mapNotNull { name ->
            resolve(name, direct, doors)?.let { name to it }
        }.toMap()
    }

    private fun ownId(body: String, known: Map<String, String>): String? {
        val own = Regex("""val ID = (.+)""").find(body)?.groupValues?.get(1)?.trim() ?: return null
        Regex("""CapabilityId\("([^"]+)"\)""").find(own)?.let { return it.groupValues[1] }
        return known[own.substringAfterLast('.').trim(' ', '}')]
    }

    private fun resolve(
        name: String,
        direct: Map<String, String>,
        doors: Map<String, String>,
        seen: Set<String> = emptySet(),
    ): String? = direct[name] ?: doors[name]?.takeIf { name !in seen }
        ?.let { resolve(it, direct, doors, seen + name) }

    const val REGISTRY = "core/flow/src/main/kotlin/com/point/core/flow/KnownCapabilityIds.kt"
}
