package com.point.core.flow

/**
 * Что считается цементом формулировки (#584, решение владельца 10.08.2026).
 *
 * Цемент — это `assertEquals` с точной человекочитаемой строкой в **ожидаемом** значении.
 * Строка-контракт (формат данных, протокол, имя файла) нарушением не является: её и правда
 * нельзя менять, не сломав обмен. Различаются они дёшево и машинно — по кириллице: текст для
 * человека в этом продукте написан по-русски, протокол — нет.
 *
 * Пояснение в трёхаргументном `assertEquals(сообщение, ожидаемое, полученное)` цементом тоже
 * не является: оно объясняет падение, а не сверяет экран. Прежний счётчик считал его наравне
 * с ожидаемым значением и заставлял переписывать тесты, которые ничего не цементировали.
 */
object CementedWording {

    fun countIn(source: String): Int = expectedLiterals(source).count(::humanReadable)

    private fun humanReadable(text: String) =
        text.length >= MIN_HUMAN && text.any { it in 'а'..'я' || it in 'А'..'Я' }

    /** Ожидаемые значения всех `assertEquals` файла — те, что являются простой строкой. */
    private fun expectedLiterals(source: String): List<String> = buildList {
        var at = source.indexOf(CALL)
        while (at >= 0) {
            val open = at + CALL.length
            val args = topLevelArgs(source, open)
            val expected = when (args.size) {
                2 -> args[0]
                3 -> args[1]
                else -> null
            }
            expected?.let(::literalOf)?.let(::add)
            at = source.indexOf(CALL, open)
        }
    }

    /** Текст простой строки-литерала, или `null` — там выражение, шаблон или что угодно ещё. */
    private fun literalOf(arg: String): String? {
        val text = arg.trim()
        if (text.length < 2 || !text.startsWith('"') || !text.endsWith('"')) return null
        if (text.startsWith("\"\"\"")) return null
        val inside = text.substring(1, text.length - 1)
        if ('"' in inside.replace("\\\"", "")) return null
        return if ("\${" in inside || Regex("""\$\w""").containsMatchIn(inside)) null else inside
    }

    /**
     * Аргументы вызова, начиная сразу после открывающей скобки. Скобки, строки и символьные
     * литералы внутри аргумента запятыми не считаются — иначе разделилась бы любая вложенная
     * функция.
     */
    private fun topLevelArgs(source: String, open: Int): List<String> {
        val args = mutableListOf<String>()
        val piece = StringBuilder()
        var depth = 0
        var i = open
        while (i < source.length) {
            val c = source[i]
            when {
                c == '"' -> {
                    val end = endOfString(source, i)
                    piece.append(source, i, end)
                    i = end
                    continue
                }

                c == '\'' -> {
                    val end = endOfChar(source, i)
                    piece.append(source, i, end)
                    i = end
                    continue
                }

                c in "([{" -> { depth++; piece.append(c) }
                c in "]}" -> { depth--; piece.append(c) }
                c == ')' && depth == 0 -> {
                    if (piece.isNotBlank()) args += piece.toString()
                    return args
                }

                c == ')' -> { depth--; piece.append(c) }
                c == ',' && depth == 0 -> { args += piece.toString(); piece.clear() }
                else -> piece.append(c)
            }
            i++
        }
        return args
    }

    private fun endOfString(source: String, start: Int): Int {
        if (source.startsWith("\"\"\"", start)) {
            val close = source.indexOf("\"\"\"", start + 3)
            return if (close < 0) source.length else close + 3
        }
        var i = start + 1
        while (i < source.length) {
            when (source[i]) {
                '\\' -> i++
                '"' -> return i + 1
            }
            i++
        }
        return source.length
    }

    private fun endOfChar(source: String, start: Int): Int {
        var i = start + 1
        while (i < source.length) {
            when (source[i]) {
                '\\' -> i++
                '\'' -> return i + 1
            }
            i++
        }
        return source.length
    }

    private const val CALL = "assertEquals("

    private const val MIN_HUMAN = 6
}
