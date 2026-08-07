package com.point.core.ui

import androidx.compose.ui.geometry.Offset
import com.point.core.model.CapabilityId

fun magnetTarget(
    dragPosition: Offset,
    centers: Map<CapabilityId, Offset>,
    radiusPx: Float,
): CapabilityId? =
    centers
        .minByOrNull { (_, center) -> (center - dragPosition).getDistanceSquared() }
        ?.takeIf { (_, center) -> (center - dragPosition).getDistance() <= radiusPx }
        ?.key
