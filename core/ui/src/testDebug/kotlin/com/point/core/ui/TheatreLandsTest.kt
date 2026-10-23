package com.point.core.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import com.point.core.flow.META_ENTITY_PHONE
import com.point.core.flow.shownKnowledge
import com.point.core.model.Because
import com.point.core.model.Bubble
import com.point.core.model.CapabilityId
import com.point.core.model.Feature
import com.point.core.model.Intent
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import com.point.core.ui.theme.PointTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Найденное приземляется в свою строку (#209).
 *
 * Полёт — картинка поверх экрана; значение живёт текстом в заголовке своего раздела всё время,
 * и на экране оно одно. Пришедшее вместе с вещью не взлетает, а с привычкой полёт снимается.
 */
@RunWith(RobolectricTestRunner::class)
class TheatreLandsTest {

    @get:Rule val compose = createComposeRule()

    private val phone = "+380671234567"
    private val bare = PointObject(
        id = "card",
        mime = "image/jpeg",
        uri = ScratchRef("/scratch/card.jpg"),
        state = ObjectState(ObjectKind.IMAGE),
        metadata = mapOf("name" to "vizitka.jpg"),
    )
    private val facts = mapOf("name" to "vizitka.jpg", META_ENTITY_PHONE to phone)
    private val shown = shownKnowledge(META_ENTITY_PHONE, phone, facts)
    private val found = bare.copy(state = ObjectState(ObjectKind.IMAGE, setOf(Feature.HAS_PHONE)), metadata = facts)

    private fun bubbles(withPhone: Boolean): List<Bubble> {
        val because = Because(META_ENTITY_PHONE, "Телефон", shown)
        val share = Bubble("x", "Поделиться", CapabilityId("share"), ObjectState(ObjectKind.IMAGE), intent = Intent.SEND)
        val call = Bubble("x", "Позвонить", CapabilityId("call"), ObjectState(ObjectKind.IMAGE), intent = Intent.OPEN, because = because)
        return if (withPhone) listOf(call, share) else listOf(share)
    }

    private var played = 0

    private fun open(start: PointObject, stage: TheatreStage = TheatreStage.FULL): (PointObject) -> Unit {
        var obj by mutableStateOf(start)
        compose.setContent {
            PointTheme(darkTheme = true) {
                FirstScreen(
                    obj = obj,
                    bubbles = bubbles(withPhone = obj.metadata.containsKey(META_ENTITY_PHONE)),
                    onBubble = {},
                    theatreStage = stage,
                    onTheatrePlayed = { played++ },
                )
            }
        }
        compose.waitForIdle()
        return { obj = it }
    }

    private fun settle() {
        compose.mainClock.advanceTimeBy(3_000)
        compose.waitForIdle()
    }

    private fun shownOnce() =
        assertEquals("значение на экране не одно", 1, compose.onAllNodesWithText(shown).fetchSemanticsNodes().size)

    @Test fun `найденное по ходу взлетает и садится в свою строку`() {
        val update = open(bare)
        update(found)
        settle()

        assertEquals(1, played)
        shownOnce()
    }

    @Test fun `знание, пришедшее с вещью, не взлетает`() {
        open(found)
        settle()

        assertEquals(0, played)
        shownOnce()
    }

    @Test fun `с привычкой полёта нет, а значение на месте`() {
        val update = open(bare, TheatreStage.LANDING)
        update(found)
        settle()

        assertEquals(0, played)
        shownOnce()
    }
}
