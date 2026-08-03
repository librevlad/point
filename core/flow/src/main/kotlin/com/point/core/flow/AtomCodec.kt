package com.point.core.flow

import java.util.Base64

/**
 * Текстовый формат слоя атомов — один и тот же для двух дорог (#257):
 *
 * - **дамп с устройства**: debug-сборка пишет прочитанный слой в файл, `adb pull` забирает —
 *   так дословные `слово+bbox+conf` с живого A34 становятся фикстурами;
 * - **фикстуры в тестах**: правило движка проверяется на снятом с устройства слое, а не на
 *   сочинённой геометрии (урок #233 — три из четырёх обещанных вещей не случились на реальном
 *   кадре, потому что тесты кормили движок текстом, набранным человеком).
 *
 * Формат построчный, по атому на строку, поля через табуляцию; текстовые поля в Base64, потому
 * что слово с реального кадра может содержать что угодно, включая табуляцию. Числа — как их
 * печатает Kotlin: формат читают тесты и человек в редакторе, а не сторонний парсер.
 *
 * Парс **строгий**: повреждённая фикстура обязана уронить тест громко. Тихое «пропустим кривую
 * строку» превратило бы битый дамп в проходящий тест — ровно та тихая ложь, от которой слой
 * атомов и лечит.
 */
object AtomCodec {

    private const val HEADER = "#point-atoms v1"
    private const val TRANSFORM = "#transform"
    private const val READER_TEXT = "#readerText"
    private const val INCOMPLETE = "#incomplete"

    fun encode(layer: AtomLayer): String = buildString {
        appendLine(HEADER)
        layer.transform?.let {
            appendLine("$TRANSFORM sample=${it.sample} rotation=${it.rotationDegrees} w=${it.uprightWidth} h=${it.uprightHeight}")
        }
        // Причина неполноты — часть дословного дампа (#262): фикстура отрезанного по времени
        // чтения без пометки выдала бы огрызок за всё, что движок увидел на кадре.
        layer.incomplete?.takeIf { it.isNotEmpty() }?.let { appendLine("$INCOMPLETE ${b64(it)}") }
        // Именно readerText, не layer.text: вычисленная сборка по полосам под ярлыком движка
        // сделала бы «дословный дамп» сочинённым — decode обязан вернуть слой без readerText,
        // если движок его не отдавал (пересборка воспроизводится из атомов той же функцией).
        layer.readerText?.takeIf { it.isNotEmpty() }?.let { appendLine("$READER_TEXT ${b64(it)}") }
        layer.atoms.forEach { a ->
            appendLine(
                listOf(
                    a.id, b64(a.text),
                    a.box.left.toString(), a.box.top.toString(), a.box.right.toString(), a.box.bottom.toString(),
                    a.confidence.toString(), b64(a.reader), b64(a.readerVersion), a.page.toString(),
                ).joinToString("\t"),
            )
        }
    }

    fun decode(text: String): AtomLayer {
        val lines = text.lines().filter { it.isNotBlank() }
        require(lines.firstOrNull() == HEADER) { "not an atom dump: first line is '${lines.firstOrNull()}'" }
        var transform: FrameTransform? = null
        var readerText: String? = null
        var incomplete: String? = null
        val atoms = mutableListOf<Atom>()
        lines.drop(1).forEach { line ->
            when {
                line.startsWith(TRANSFORM) -> {
                    val kv = line.removePrefix(TRANSFORM).trim().split(" ")
                        .associate { it.substringBefore("=") to it.substringAfter("=").toInt() }
                    transform = FrameTransform(
                        sample = kv.getValue("sample"),
                        rotationDegrees = kv.getValue("rotation"),
                        uprightWidth = kv.getValue("w"),
                        uprightHeight = kv.getValue("h"),
                    )
                }
                line.startsWith(READER_TEXT) -> readerText = unb64(line.removePrefix(READER_TEXT).trim())
                line.startsWith(INCOMPLETE) -> incomplete = unb64(line.removePrefix(INCOMPLETE).trim())
                else -> {
                    val f = line.split("\t")
                    require(f.size == 10) { "atom line must have 10 fields, got ${f.size}: '$line'" }
                    atoms += Atom(
                        id = f[0], text = unb64(f[1]),
                        box = Box(f[2].toFloat(), f[3].toFloat(), f[4].toFloat(), f[5].toFloat()),
                        confidence = f[6].toFloat(),
                        reader = unb64(f[7]), readerVersion = unb64(f[8]), page = f[9].toInt(),
                    )
                }
            }
        }
        return AtomLayer(atoms, readerText = readerText, transform = transform, incomplete = incomplete)
    }

    private fun b64(s: String): String = Base64.getEncoder().encodeToString(s.toByteArray(Charsets.UTF_8))
    private fun unb64(s: String): String = String(Base64.getDecoder().decode(s), Charsets.UTF_8)
}
