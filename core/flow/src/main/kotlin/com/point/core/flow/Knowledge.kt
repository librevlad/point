package com.point.core.flow

import com.point.core.model.PointObject

fun continuesObject(before: PointObject, after: PointObject): Boolean = before.uri == after.uri

fun carryKnowledge(known: PointObject, produced: PointObject): PointObject = produced.copy(
    id = known.id,
    state = known.state.features.fold(produced.state) { state, feature -> state.with(feature) },
    metadata = known.metadata + produced.metadata,
    provenance = maxOf(known.provenance, produced.provenance),
    sourceObjects = produced.sourceObjects.ifEmpty { known.sourceObjects },
    creatorAction = produced.creatorAction ?: known.creatorAction,
)
