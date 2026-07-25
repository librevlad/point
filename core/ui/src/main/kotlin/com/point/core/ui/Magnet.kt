package com.point.core.ui

import androidx.compose.ui.geometry.Offset
import com.point.core.model.CapabilityId

/**
 * #115 drag-to-connect: while the object is being dragged, the nearest bubble within
 * [radiusPx] of [dragPosition] is the connection candidate — it lights up, and releasing
 * the object there fires the action. Pure geometry, unit-tested; the gesture layer only
 * feeds positions in.
 */
fun magnetTarget(
    dragPosition: Offset,
    centers: Map<CapabilityId, Offset>,
    radiusPx: Float,
): CapabilityId? =
    centers
        .minByOrNull { (_, center) -> (center - dragPosition).getDistanceSquared() }
        ?.takeIf { (_, center) -> (center - dragPosition).getDistance() <= radiusPx }
        ?.key
