package com.point.data

import com.point.core.flow.Realizer
import com.point.core.model.ActionResult
import com.point.core.model.Findings
import com.point.core.model.PointObject

/**
 * Исследование теперь обычный Realizer- знание приезжает исходом «выполнено» (ADR-0001 §18).
 */
internal suspend fun Realizer.look(obj: PointObject): Findings {
    val result = perform(obj, null)
    check(result is ActionResult.Done) { "исследование обязано вернуть выполнено, а вернуло-$result" }
    return result.findings ?: Findings()
}
