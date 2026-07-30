package com.point.core.flow

/**
 * The document broken into addressable pieces (#222, шаг 6) — the only thing a model is ever
 * shown.
 *
 * [id] is a **local** identifier: `P1`, `P2`, … today; `T1.R2.C3` for a table cell and `I1` for a
 * picture when those extractors land. Local means produced by deterministic code, before any
 * model runs, and meaningless outside this one document.
 *
 * **Why local ids and not the graph.** A classifier could be handed the Point graph and asked to
 * add to it — and then the graph's shape would become part of the prompt contract, so every change
 * to the data model would be a change to every prompt, and every model swap a re-verification of
 * the model's understanding of our types. Layout ids belong to the page instead: paragraphs and
 * cells physically exist on it, they are ours to number, and no model has to know what an
 * `Organization` is to point at the line that mentions one.
 */
data class LayoutElement(val id: String, val text: String)

/**
 * Splits already-read text into addressable paragraphs.
 *
 * A line is the unit because that is what OCR produces and what a form is: on the parcel screen
 * and on a CMR, one visual row is one fact. Geometry-aware blocks and tables come with the
 * extractors that can see geometry (#222, шаг 8); the ids they mint slot into the same contract.
 */
fun layoutOf(text: String, limit: Int = MAX_LAYOUT_ELEMENTS): List<LayoutElement> =
    text.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .take(limit)
        .mapIndexed { i, line -> LayoutElement("P${i + 1}", line.take(MAX_ELEMENT_CHARS)) }
        .toList()

/** A cap, not a judgement: a prompt has a budget, and a 300-page PDF would blow it. Whatever is
 *  dropped is dropped visibly — the caller can tell, because the element count is right there. */
const val MAX_LAYOUT_ELEMENTS = 120

private const val MAX_ELEMENT_CHARS = 300
