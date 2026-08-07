package com.point.core.flow

sealed interface JsonValue {
    data class Str(val value: String) : JsonValue
    data class Num(val value: Double) : JsonValue
    data class Bool(val value: Boolean) : JsonValue
    data object Null : JsonValue
    data class Arr(val items: List<JsonValue>) : JsonValue
    data class Obj(val fields: Map<String, JsonValue>) : JsonValue
}

fun parseJson(text: String): JsonValue? = runCatching {
    val reader = JsonReader(text)
    val value = reader.readValue()
    reader.skipSpace()
    if (!reader.done()) null else value
}.getOrNull()

fun JsonValue?.str(name: String): String? = ((this as? JsonValue.Obj)?.fields?.get(name) as? JsonValue.Str)?.value

fun JsonValue?.long(name: String): Long? = ((this as? JsonValue.Obj)?.fields?.get(name) as? JsonValue.Num)?.value?.toLong()

fun JsonValue?.bool(name: String): Boolean? = ((this as? JsonValue.Obj)?.fields?.get(name) as? JsonValue.Bool)?.value

fun JsonValue?.array(name: String): List<JsonValue> =
    ((this as? JsonValue.Obj)?.fields?.get(name) as? JsonValue.Arr)?.items ?: emptyList()

fun jsonObject(vararg fields: Pair<String, String>): String =
    fields.joinToString(",", "{", "}") { (k, v) -> "\"${escapeJson(k)}\":\"${escapeJson(v)}\"" }

fun jsonObject(fields: List<Pair<String, String>>, flags: List<Pair<String, Boolean>>): String =
    (fields.map { (k, v) -> "\"${escapeJson(k)}\":\"${escapeJson(v)}\"" } +
        flags.map { (k, v) -> "\"${escapeJson(k)}\":$v" })
        .joinToString(",", "{", "}")

private fun escapeJson(s: String): String = buildString(s.length + 8) {
    for (ch in s) when {
        ch == '"' -> append("\\\"")
        ch == '\\' -> append("\\\\")
        ch == '\n' -> append("\\n")
        ch == '\r' -> append("\\r")
        ch == '\t' -> append("\\t")
        ch < ' ' -> append("\\u%04x".format(ch.code))
        else -> append(ch)
    }
}

private class JsonReader(private val text: String) {
    private var at = 0

    fun done(): Boolean = at >= text.length

    fun skipSpace() {
        while (at < text.length && text[at].isWhitespace()) at++
    }

    fun readValue(): JsonValue? {
        skipSpace()
        if (done()) return null
        return when (text[at]) {
            '{' -> readObject()
            '[' -> readArray()
            '"' -> readString()?.let(JsonValue::Str)
            't' -> literal("true", JsonValue.Bool(true))
            'f' -> literal("false", JsonValue.Bool(false))
            'n' -> literal("null", JsonValue.Null)
            else -> readNumber()
        }
    }

    private fun literal(word: String, value: JsonValue): JsonValue? =
        if (text.startsWith(word, at)) value.also { at += word.length } else null

    private fun readNumber(): JsonValue? {
        val start = at
        if (at < text.length && (text[at] == '-' || text[at] == '+')) at++
        while (at < text.length && (text[at].isDigit() || text[at] in ".eE+-")) at++
        return text.substring(start, at).toDoubleOrNull()?.let(JsonValue::Num)
    }

    private fun readString(): String? {
        if (text[at] != '"') return null
        at++
        val out = StringBuilder()
        while (at < text.length) {
            when (val ch = text[at++]) {
                '"' -> return out.toString()
                '\\' -> {
                    if (at >= text.length) return null
                    when (val esc = text[at++]) {
                        '"', '\\', '/' -> out.append(esc)
                        'b' -> out.append('\b')
                        'f' -> out.append('\u000C')
                        'n' -> out.append('\n')
                        'r' -> out.append('\r')
                        't' -> out.append('\t')
                        'u' -> {
                            if (at + 4 > text.length) return null
                            val code = text.substring(at, at + 4).toIntOrNull(16) ?: return null
                            out.append(code.toChar())
                            at += 4
                        }
                        else -> return null
                    }
                }
                else -> out.append(ch)
            }
        }
        return null
    }

    private fun readArray(): JsonValue? {
        at++
        val items = mutableListOf<JsonValue>()
        skipSpace()
        if (at < text.length && text[at] == ']') { at++; return JsonValue.Arr(items) }
        while (true) {
            items += readValue() ?: return null
            skipSpace()
            if (done()) return null
            when (text[at++]) {
                ',' -> Unit
                ']' -> return JsonValue.Arr(items)
                else -> return null
            }
        }
    }

    private fun readObject(): JsonValue? {
        at++
        val fields = LinkedHashMap<String, JsonValue>()
        skipSpace()
        if (at < text.length && text[at] == '}') { at++; return JsonValue.Obj(fields) }
        while (true) {
            skipSpace()
            if (done()) return null
            val key = readString() ?: return null
            skipSpace()
            if (done() || text[at++] != ':') return null
            fields[key] = readValue() ?: return null
            skipSpace()
            if (done()) return null
            when (text[at++]) {
                ',' -> Unit
                '}' -> return JsonValue.Obj(fields)
                else -> return null
            }
        }
    }
}
