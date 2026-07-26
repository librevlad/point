package com.point.core.flow

import com.point.core.model.Feature

/**
 * The semantic level of understanding (#89): `semantic.*` metadata describes what the
 * object IS (a meeting, a purchase, a recipe, a job posting), one level above the
 * syntactic `entity.*` facts (a phone, a date). Stored on the object, so the
 * understanding survives history re-opens and travels to the PC with the object.
 */
const val META_SEMANTIC_PREFIX = "semantic."

/** One of [SEMANTIC_TYPES]' keys — the object's recognised nature. */
const val META_SEMANTIC_TYPE = META_SEMANTIC_PREFIX + "type"

/** A one-line human summary of the object («Борщ на говяжьем бульоне»). */
const val META_SEMANTIC_SUMMARY = META_SEMANTIC_PREFIX + "summary"

/**
 * The closed whitelist of semantic types and the features they light. Closed on
 * purpose: a type is only worth recognising once something in the graph reacts to it —
 * extend the map and the contract together with the reacting capability.
 */
val SEMANTIC_TYPES: Map<String, Feature> = mapOf(
    "meeting" to Feature.IS_MEETING,
    "purchase" to Feature.IS_PURCHASE,
    "recipe" to Feature.IS_RECIPE,
    "job" to Feature.IS_JOB,
)
