package com.point.core.flow

/**
 * «Find waybill numbers» — one extractor, one line of spec (#222).
 *
 * This is the shape every extractor in the pipeline takes: a tiny, single-purpose rule that can
 * be improved on its own without touching anything else. Pure and on-device — no model, no
 * network, so it runs in the cheap wave.
 *
 * **Why this exists at all.** ML Kit reads `20 4514 9154 9395` off a parcel screenshot and calls
 * it a phone; [isPlausible] then correctly drops it, because 14 digits is not something you dial.
 * The judgement is right and stays — what was missing is anyone to pick the number up afterwards.
 * It was the single most useful thing on the screen and it fell through the floor.
 *
 * **On the checksum.** A structural rule only: 14 digits, optionally grouped by spaces. Nova
 * Poshta does publish waybills in this shape, but no verified check-digit algorithm went into
 * this code — inventing one would reject real numbers, which is worse than a rare false
 * positive. Hence [WAYBILL_CONFIDENCE] below 1: the pipeline is told this reading is structural,
 * not verified, and consensus or a later validator can raise it.
 */

/** A digit run of exactly 14 digits, optionally grouped by spaces, not glued to other digits.
 *  `internal`, не `private`: разметка улик ([ruleEvidence]) помечает ту же форму на атомах —
 *  два регекса «похоже на трек» разъехались бы при первой же правке. */
internal val WAYBILL_SHAPED = Regex("""(?<!\d)\d[\d ]{11,20}\d(?!\d)""")

/** Structural match only — see the note on the checksum above. */
const val WAYBILL_CONFIDENCE = 0.8f

/**
 * Waybill-shaped numbers in [text], normalised to single spaces and de-duplicated in the order
 * they appear. Empty when there are none — the common case, and the reason this is cheap.
 */
fun waybillNumbers(text: String): List<String> =
    WAYBILL_SHAPED.findAll(text)
        .map { it.value.trim() }
        .filter { it.count(Char::isDigit) == WAYBILL_DIGITS }
        .map { it.replace(MULTI_SPACE, " ") }
        .distinct()
        .toList()

internal const val WAYBILL_DIGITS = 14
private val MULTI_SPACE = Regex(""" {2,}""")
