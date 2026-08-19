package com.point.core.flow

import com.point.core.flow.PcActionFit.fitsObject
import com.point.core.model.Feature
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Годится ли чужое действие объекту — одно правило на обе стороны (#1092, #1144).
 *
 * Компьютер спрашивал вид и признаки, телефон — только вид: действие компьютера «только для
 * ссылки» на телефоне предлагалось объекту без ссылки.
 */
class OneRuleForNeighbourActionsTest {

    private val urlOnly = PcRemoteAction("open-url", "Открыть ссылку", kinds = setOf("IMAGE"), features = setOf("HAS_URL"))

    @Test fun `действие с признаком не предлагается объекту без признака`() {
        assertFalse(urlOnly.fitsObject(ObjectState(ObjectKind.IMAGE)))
    }

    @Test fun `действие с признаком предлагается объекту с признаком`() {
        assertTrue(urlOnly.fitsObject(ObjectState(ObjectKind.IMAGE, setOf(Feature.HAS_URL))))
    }

    @Test fun `вид объекта по-прежнему обязан совпасть`() {
        assertFalse(urlOnly.fitsObject(ObjectState(ObjectKind.PDF, setOf(Feature.HAS_URL))))
    }

    @Test fun `действие без ограничений годится любому`() {
        assertTrue(PcRemoteAction("open", "Открыть").fitsObject(ObjectState(ObjectKind.PDF)))
    }
}
