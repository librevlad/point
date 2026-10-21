package com.point.core.model

import java.util.Locale

/**
 * Язык, на котором Point говорит с человеком (#1473): английский, украинский или русский.
 *
 * По умолчанию — язык устройства: `Locale.getDefault()` на телефоне — язык системы или выбранный
 * для Point в настройках Android, на компьютере — язык Windows. Ни один из трёх не подошёл —
 * английский: его поймёт больше людей, чем любой другой из трёх.
 */
enum class Lang(val tag: String) {
    EN("en"),
    UK("uk"),
    RU("ru"),
    ;

    companion object {
        fun of(locale: Locale): Lang = when (locale.language) {
            "uk" -> UK
            "ru" -> RU
            else -> EN
        }

        fun of(tag: String): Lang? = entries.firstOrNull { it.tag == tag.lowercase().substringBefore('-') }

        /**
         * Читается на каждом обращении: язык системы человек может поменять, не перезапуская Point.
         *
         * Свойство `point.lang` важнее языка системы: им закреплён язык тестов (они читают русские
         * фразы, а машина CI английская), и им же можно запустить Point на другом языке, не трогая
         * язык всей системы. Сам `Locale` тестам не подменяется: от него зависят разбор сумм и дат,
         * и тесты обязаны видеть его таким же, как раньше.
         */
        fun current(): Lang = System.getProperty(PROPERTY)?.let(::of) ?: of(Locale.getDefault())

        const val PROPERTY = "point.lang"
    }
}

/**
 * Строка на языке человека. Тексты в коде пишутся по-русски, как и раньше; русская строка —
 * ключ каталога `point/i18n/texts.tsv` (ресурс этого модуля), где рядом стоят украинский и
 * английский. Проверка в `:checks` не даёт появиться `tr("…")` без перевода.
 *
 * Подстановки — `{0}`, `{1}`… по порядку аргументов: порядок слов в переводе бывает другим,
 * поэтому значения не склеиваются с кусками фразы, а встают в неё на свои места.
 *
 * Перевода нет — русский оригинал, а не пустое место: недопереведённое лучше молчащего.
 */
fun tr(ru: String, vararg args: Any?): String {
    val text = when (val lang = Lang.current()) {
        Lang.RU -> ru
        else -> TrCatalog.find(ru, lang) ?: ru
    }
    return if (args.isEmpty()) text else fill(text, args)
}

/**
 * Слово при числе. Три русские формы (1 / 2–4 / 5+) — ключ; украинский устроен так же,
 * английскому нужны две — единственное (перевод `one`) и множественное (перевод `many`).
 */
fun trPlural(n: Long, one: String, few: String, many: String): String {
    val lang = Lang.current()
    if (lang == Lang.EN) return if (n == 1L) tr(one) else tr(many)
    val mod100 = Math.floorMod(n, 100L)
    val mod10 = Math.floorMod(n, 10L)
    val form = when {
        mod100 in 11..14 -> many
        mod10 == 1L -> one
        mod10 in 2..4 -> few
        else -> many
    }
    return tr(form)
}

fun trPlural(n: Int, one: String, few: String, many: String): String = trPlural(n.toLong(), one, few, many)

private fun fill(text: String, args: Array<out Any?>): String {
    val out = StringBuilder(text.length + 16)
    var i = 0
    while (i < text.length) {
        val c = text[i]
        if (c == '{') {
            val close = text.indexOf('}', i + 1)
            val index = if (close > i + 1) text.substring(i + 1, close).toIntOrNull() else null
            if (index != null && index in args.indices) {
                out.append(args[index])
                i = close + 1
                continue
            }
        }
        out.append(c)
        i++
    }
    return out.toString()
}

/**
 * Каталог переводов: читается с classpath один раз, при первом не-русском обращении.
 * Русский язык его не читает вовсе.
 *
 * Строка файла — `русский<TAB>украинский<TAB>английский`; внутри `\n`, `\t` и `\\`.
 */
object TrCatalog {
    const val RESOURCE = "/point/i18n/texts.tsv"

    private val table: Map<String, Array<String>> by lazy { load() }

    fun find(ru: String, lang: Lang): String? {
        val row = table[ru] ?: return null
        return when (lang) {
            Lang.UK -> row[0]
            Lang.EN -> row[1]
            Lang.RU -> ru
        }.takeIf { it.isNotEmpty() }
    }

    fun parse(lines: Sequence<String>): Map<String, Array<String>> {
        val out = HashMap<String, Array<String>>(4096)
        lines.forEach { line ->
            if (line.isBlank()) return@forEach
            val cells = line.split('\t')
            if (cells.size != 3) return@forEach
            out[unescape(cells[0])] = arrayOf(unescape(cells[1]), unescape(cells[2]))
        }
        return out
    }

    fun escape(s: String): String = s.replace("\\", "\\\\").replace("\n", "\\n").replace("\t", "\\t")

    fun unescape(s: String): String {
        if ('\\' !in s) return s
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    'n' -> out.append('\n')
                    't' -> out.append('\t')
                    '\\' -> out.append('\\')
                    else -> out.append(c).append(s[i + 1])
                }
                i += 2
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }

    private fun load(): Map<String, Array<String>> {
        val stream = TrCatalog::class.java.getResourceAsStream(RESOURCE) ?: return emptyMap()
        return stream.bufferedReader(Charsets.UTF_8).useLines { parse(it) }
    }
}
