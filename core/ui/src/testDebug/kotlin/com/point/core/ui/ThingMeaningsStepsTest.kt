package com.point.core.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.point.core.flow.META_ALT_SUFFIX
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
import com.point.core.model.Relation
import com.point.core.model.RelationType
import com.point.core.model.ScratchRef
import com.point.core.ui.theme.PointTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Экран объекта — вещь, её смыслы и естественные шаги.
 *
 * Найденное значение приходит со своими продолжениями и стоит на экране один раз: заголовком
 * своего раздела, где его можно взять касанием и где видно, если Point в нём сомневается.
 * Сделанное из вещи стоит у неё же (P4), а у сделанного — дорога назад.
 */
@RunWith(RobolectricTestRunner::class)
class ThingMeaningsStepsTest {

    @get:Rule val compose = createComposeRule()

    private val phone = "+380671234567"
    private val facts = mapOf("name" to "vizitka.jpg", META_ENTITY_PHONE to phone)
    private val shown = shownKnowledge(META_ENTITY_PHONE, phone, facts)

    private val card = PointObject(
        id = "card",
        mime = "image/jpeg",
        uri = ScratchRef("/scratch/card.jpg"),
        state = ObjectState(ObjectKind.IMAGE, setOf(Feature.HAS_PHONE)),
        metadata = facts,
    )

    private val because = Because(META_ENTITY_PHONE, "Телефон", shown)

    private fun bubble(id: String, title: String, intent: Intent, because: Because? = null) =
        Bubble("x", title, CapabilityId(id), ObjectState(ObjectKind.IMAGE), intent = intent, because = because)

    private val bubbles = listOf(
        bubble("call", "Позвонить", Intent.OPEN, because),
        bubble("sms", "Сообщение", Intent.OPEN, because),
        bubble("share", "Поделиться", Intent.SEND),
    )

    private fun show(obj: PointObject, found: List<PointObject> = emptyList(), relations: List<Relation> = emptyList()) {
        compose.setContent {
            PointTheme(darkTheme = true) {
                FirstScreen(obj = obj, bubbles = bubbles, onBubble = {}, found = found, relations = relations)
            }
        }
    }

    @Test fun `номер стоит заголовком своих действий и больше нигде`() {
        show(card)

        assertEquals("номер повторён на экране", 1, compose.onAllNodesWithText(shown).fetchSemanticsNodes().size)
        compose.onNodeWithText("ТЕЛЕФОН").assertExists()
        compose.onNodeWithText("Позвонить").assertExists()
        compose.onNodeWithText("Сообщение").assertExists()
        compose.onNodeWithText("Нашёл телефон").assertDoesNotExist()
    }

    @Test fun `сама вещь — своим разделом`() {
        show(card)

        compose.onNodeWithText("С ИЗОБРАЖЕНИЕМ").assertExists()
        compose.onNodeWithText("Поделиться").assertExists()
    }

    @Test fun `значение берётся касанием`() {
        show(card)

        compose.onNodeWithText(shown).performClick()

        compose.onNodeWithText("Скопировано").assertExists()
    }

    @Test fun `спор прочтений виден там, где решают позвонить`() {
        show(card.copy(metadata = facts + (META_ENTITY_PHONE + META_ALT_SUFFIX to "+380671234587")))

        compose.onNodeWithText("или: +380671234587").assertExists()
    }

    @Test fun `сделанное стоит у исходника, а у сделанного — дорога назад`() {
        val pdf = PointObject(
            "made", "application/pdf", ScratchRef("/scratch/vizitka.pdf"), ObjectState(ObjectKind.PDF),
            mapOf("name" to "vizitka.pdf"),
        )
        val born = Relation(pdf.id, RelationType.DERIVED_FROM, card.id)

        show(card, found = listOf(pdf), relations = listOf(born))
        compose.onNodeWithText(MADE_HERE_LABEL.uppercase()).assertExists()
        compose.onNodeWithText("vizitka.pdf").assertExists()
    }

    @Test fun `у сделанного видно, из чего оно`() {
        val pdf = PointObject(
            "made", "application/pdf", ScratchRef("/scratch/vizitka.pdf"), ObjectState(ObjectKind.PDF),
            mapOf("name" to "vizitka.pdf"),
        )
        compose.setContent {
            PointTheme(darkTheme = true) {
                FirstScreen(
                    obj = pdf,
                    bubbles = emptyList(),
                    onBubble = {},
                    found = listOf(card),
                    relations = listOf(Relation(pdf.id, RelationType.DERIVED_FROM, card.id)),
                )
            }
        }

        compose.onNodeWithText("$MADE_FROM_LABEL vizitka.jpg ›").assertExists()
    }

    @Test fun `без находок у вещи нет подсвеченного главного — есть честный выбор`() {
        val plain = card.copy(state = ObjectState(ObjectKind.IMAGE), metadata = mapOf("name" to "x.jpg"))
        val sections = actionSections(
            listOf(bubble("ocr", "Распознать текст", Intent.UNDERSTAND), bubble("share", "Поделиться", Intent.SEND)),
            plain.state,
            thingTitle(plain),
        )

        assertEquals(false, sections.any { it.leads })
    }
}
