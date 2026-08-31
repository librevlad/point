package com.point.core.flow

data class CropEvidence(
    val imagePath: String,
    val region: Box,
    val uprightDegrees: Int = 0,
    val purpose: CropPurpose = CropPurpose.GLANCE,
)

enum class CropPurpose {

    GLANCE,

    READING,
}

fun readingCropUpscale(
    widthPx: Int,
    heightPx: Int,
    target: Int = READING_BAND_PX,
    budgetPx: Long = READING_CROP_BUDGET_PX,
): Int {
    if (widthPx <= 0 || heightPx <= 0) return 1
    var scale = 1
    while (scale < MAX_READING_UPSCALE && heightPx.toLong() * scale < target) scale++

    while (scale > 1 && widthPx.toLong() * heightPx * scale * scale > budgetPx) scale--
    return scale
}

const val READING_BAND_PX = 120

private const val MAX_READING_UPSCALE = 4

private const val READING_CROP_BUDGET_PX = 4_000_000L

class EvidenceImage(
    val bytes: ByteArray,
    val widthPx: Int,
    val heightPx: Int,

    val extension: String = "jpg",
)

interface EvidenceCropper {
    suspend fun crop(evidence: CropEvidence): EvidenceImage?
}

const val MAX_EVIDENCE_CROPS = 12

fun AtomLayer.locate(fragment: String): Box? = locateIn(lines(), fragment)

/**
 * Уверенно ли прочитано это значение (#1109).
 *
 * Ридер знает про каждое слово, насколько он в нём уверен, и знание это до сих пор кончалось
 * на самом слое: значение, собранное из сомнительных слов, приходило к человеку таким же
 * спокойным, как прочитанное чисто. Отсюда ложная дата рядом с верной — и ни признака, что
 * одну из них Point прочитал плохо.
 *
 * `null` — про это значение слой ничего не говорит: его слов на странице не нашлось. Молчание
 * не превращается ни в сомнение, ни в уверенность.
 */
fun AtomLayer.readConfidently(value: String, below: Float = AtomLayer.CONFIDENT_ENOUGH): Boolean? {
    val wanted = evidenceTokens(value)
    if (wanted.isEmpty()) return null
    val backing = atoms.filter { evidenceToken(it.text) in wanted }
    if (backing.isEmpty()) return null
    return backing.none { it.confidence < below }
}

private fun AtomLayer.locateIn(lines: List<List<Atom>>, fragment: String): Box? {
    val wanted = evidenceTokens(fragment)
    if (wanted.isEmpty()) return null
    val scored = lines
        .map { line -> line to wanted.count { token -> line.any { evidenceToken(it.text) == token } } }
    val best = scored.maxByOrNull { it.second } ?: return null
    if (best.second == 0) return insideAtom(fragment)
    if (scored.count { it.second == best.second } > 1) return null
    if (best.second == 1) {
        val matched = wanted.firstOrNull { token -> best.first.any { evidenceToken(it.text) == token } }
        if (matched == null || matched.length < DISTINCT_TOKEN) return null
    }
    val box = best.first.map { transform?.toUpright(it.box) ?: it.box }.reduce(Box::union)
    val pad = box.height * PAD_SHARE
    val padded = Box(box.left - pad, box.top - pad, box.right + pad, box.bottom + pad)
    return transform?.toRaw(padded) ?: padded
}

fun List<DocBlock>.withCropEvidence(
    layer: AtomLayer?,
    imagePath: String?,
    limit: Int = MAX_EVIDENCE_CROPS,
): List<DocBlock> {
    if (layer == null || layer.atoms.isEmpty() || imagePath.isNullOrBlank()) return this
    val degrees = layer.transform?.rotationDegrees ?: 0

    val lines = layer.lines()
    var attached = 0
    var bare = 0
    val blocks = map { block ->
        if (!block.uncertain || block.evidence != null) return@map block
        val region = layer.locateIn(lines, block.text)
        if (region == null || attached >= limit) {
            bare++
            return@map block
        }
        attached++
        block.copy(evidence = CropEvidence(imagePath, region, degrees))
    }
    if (bare == 0) return blocks
    val note = "Улик приложено $attached из ${attached + bare} помеченных фрагментов: " +
        "у остальных не нашлось однозначного места на снимке или сработал предел на документ. " +
        "Сверяйте их с исходником."
    return blocks + DocBlock(note, DocStyle.NORMAL)
}

/**
 * Место значения, которое лежит ВНУТРИ атома (#1292).
 *
 * Атомом бывает не слово, а целая строка: так отдаёт свой ответ читатель на устройстве, и
 * так же приходит текст, снятый чужими глазами. Совпадение с атомом целиком тогда не
 * находится никогда — почта `tester1@example.com` стоит внутри строки «Іванов Іван
 * tester1@example.com +380…», — и у ста девятнадцати найденных почт на длинном скриншоте не
 * было ни одного места: перейти нельзя было ни к одной.
 *
 * Область получается приблизительной — доля строки по длине значения, — и это честная цена:
 * дробность знает только читатель, а перевод отвечает за место, а не за дробность (см.
 * пояснение к `layerInRawFrame`). Пальцем человек по такой области попадает, и это ровно то,
 * ради чего место и нужно.
 *
 * Ищется по одному атому, а не по строке из нескольких: у собранной строки нет своей
 * геометрии, из которой можно взять долю, — только у атома, который читатель отдал целиком.
 */
private fun AtomLayer.insideAtom(fragment: String): Box? {
    val wanted = fragment.trim()
    if (wanted.length < INSIDE_MIN_LENGTH) return null
    val found = atoms.mapNotNull { atom ->
        val at = atom.text.indexOf(wanted, ignoreCase = true)
        if (at < 0) null else atom to at
    }
    if (found.size != 1) return null
    val (atom, at) = found.single()
    val whole = atom.text.length
    if (whole <= 0) return null

    val box = transform?.toUpright(atom.box) ?: atom.box
    val share = box.width / whole
    val left = box.left + share * at
    val right = box.left + share * (at + wanted.length)
    val pad = box.height * PAD_SHARE
    val padded = Box(left - pad, box.top - pad, right + pad, box.bottom + pad)
    return transform?.toRaw(padded) ?: padded
}

private fun evidenceTokens(fragment: String): Set<String> =
    fragment.replace("⚠", " ").replace("~~", " ")
        .split(WHITESPACE)
        .map(::evidenceToken)
        .filter { it.length >= MIN_TOKEN }
        .toSet()

private fun evidenceToken(raw: String): String =
    raw.lowercase().trim { !it.isLetterOrDigit() }

private val WHITESPACE = Regex("""\s+""")

private const val MIN_TOKEN = 2

private const val DISTINCT_TOKEN = 4

private const val PAD_SHARE = 0.35f

/**
 * Короче этого значение внутри строки не ищется: «12» или «ул» найдутся в половине строк
 * страницы и покажут человеку случайное место. Молчание честнее места, показанного мимо.
 */
private const val INSIDE_MIN_LENGTH = 5
