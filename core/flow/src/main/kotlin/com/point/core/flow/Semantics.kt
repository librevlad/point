package com.point.core.flow

import com.point.core.model.Feature

const val META_SEMANTIC_PREFIX = "semantic."

const val META_SEMANTIC_TYPE = META_SEMANTIC_PREFIX + "type"

const val META_SEMANTIC_SUMMARY = META_SEMANTIC_PREFIX + "summary"

val SEMANTIC_TYPES: Map<String, Feature> = mapOf(
    "meeting" to Feature.IS_MEETING,
    "purchase" to Feature.IS_PURCHASE,
    "recipe" to Feature.IS_RECIPE,
    "job" to Feature.IS_JOB,
)
