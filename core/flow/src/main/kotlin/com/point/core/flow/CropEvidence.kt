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

private fun AtomLayer.locateIn(lines: List<List<Atom>>, fragment: String): Box? {
    val wanted = evidenceTokens(fragment)
    if (wanted.isEmpty()) return null
    val scored = lines
        .map { line -> line to wanted.count { token -> line.any { evidenceToken(it.text) == token } } }
    val best = scored.maxByOrNull { it.second } ?: return null
    if (best.second == 0) return null
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
