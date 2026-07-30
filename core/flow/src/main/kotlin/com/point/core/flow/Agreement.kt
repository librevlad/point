package com.point.core.flow

/**
 * Several independent readings of the same thing, reconciled into one (#222, шаг 7).
 *
 * Lifted out of [reconcile], which had this logic living inside a table-cell loop. The mechanics
 * are unchanged — plurality after folding away format noise — but a table cell is not the only
 * place two sources read the same thing and disagree. A phone number found by the on-device
 * extractor and again by a model is the same situation with no table in sight.
 *
 * [agreed] false means the sources contradicted each other. That is worth carrying rather than
 * hiding: a value nobody agrees on is not the same as a value everybody agrees on, and a product
 * that shows them identically is lying by omission.
 */
data class Agreement(
    /** The plurality reading, raw — never a normalised or invented form. */
    val value: String,
    /** True when every reading said the same thing, once format noise is folded away. */
    val agreed: Boolean,
    /** The distinct readings when they disagreed; empty when they did not. */
    val candidates: List<String>,
)

/** Fold-away for agreement: case, spacing, dashes, and the ⚠/~~strike~~ markers don't count as a diff. */
internal fun normConsensus(s: String): String =
    s.lowercase().replace("⚠", "").replace("~~", "")
        .replace(Regex("""[\s\-–—.,]+"""), "")

/**
 * Votes [readings] of one thing. Null when nothing was read at all — an absent value is not a
 * disagreement, and the caller decides what absence means.
 *
 * On a tie the **first** reading wins, so the caller controls precedence by ordering: putting
 * what is already known first means a fresh source has to actually outvote it, not merely
 * arrive later.
 */
fun agree(readings: List<String>): Agreement? {
    val present = readings.map { it.trim() }.filter { it.isNotBlank() }
    if (present.isEmpty()) return null

    val byNorm = present.groupBy(::normConsensus)
    val top = byNorm.maxByOrNull { it.value.size }!!
    val pick = top.value.first() // a raw value of the plurality group
    return if (byNorm.size == 1) {
        Agreement(pick, agreed = true, candidates = emptyList())
    } else {
        Agreement(pick, agreed = false, candidates = present.distinct())
    }
}

/** Metadata suffix holding the readings a fact was disputed between: `entity.address.alt`. */
const val META_ALT_SUFFIX = ".alt"

/** The readings are separated by newlines — metadata is stored as JSON, which escapes them. */
private const val ALT_SEPARATOR = "\n"

/** The stored form of [alternativesOf]. Exists so exactly one place knows the separator —
 *  a platform line separator here would make the journal unreadable on the other device. */
fun altValue(readings: List<String>): String = readings.joinToString(ALT_SEPARATOR)

/** The alternative readings recorded for [key], or empty when the sources agreed. */
fun alternativesOf(metadata: Map<String, String>, key: String): List<String> =
    metadata[key + META_ALT_SUFFIX]?.split(ALT_SEPARATOR)?.filter { it.isNotBlank() }.orEmpty()

/**
 * Merges freshly read facts into what the object already knew — **by vote, not by overwrite**.
 *
 * Before this, a later source simply won: the deep-understand model's address replaced the
 * on-device extractor's, and nobody could tell whether they had agreed. Now a contradiction is
 * recorded in `<key>.alt`, and what is known keeps precedence on a tie: a paid guess does not
 * get to overrule a local reading just by arriving second.
 */
fun mergeFacts(known: Map<String, String>, fresh: Map<String, String>): Map<String, String> {
    val merged = LinkedHashMap(known)
    fresh.forEach { (key, value) ->
        if (key.endsWith(META_ALT_SUFFIX)) return@forEach
        val was = known[key]
        if (was.isNullOrBlank()) {
            merged[key] = value
            return@forEach
        }
        val verdict = agree(listOf(was, value)) ?: return@forEach
        merged[key] = verdict.value
        if (verdict.agreed) {
            merged.remove(key + META_ALT_SUFFIX)
        } else {
            // Every reading, winner included — «или это, или то» is the honest shape of a tie.
            val all = (alternativesOf(known, key) + verdict.candidates).distinct()
            merged[key + META_ALT_SUFFIX] = altValue(all)
        }
    }
    return merged
}

/** What a fact is worth when its sources contradict each other: a coin flip, and shown as one. */
const val DISPUTED_CONFIDENCE = 0.5f
