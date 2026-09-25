package com.point.core.flow

import com.point.core.model.Because
import com.point.core.model.CapabilityId
import com.point.core.model.Feature
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * «Потому что» у действия: действие продолжает найденное значение, если без найденных
 * значений та же вещь его не предлагает (вещь → смыслы → естественные шаги).
 */
class ActionBecauseTest {

    private val phone = "+380671234567"

    private fun graph(
        kind: ObjectKind = ObjectKind.IMAGE,
        features: Set<Feature> = emptySet(),
        facts: Map<String, String> = emptyMap(),
    ) = GraphState(PointObject("o", "image/jpeg", ScratchRef("/o"), ObjectState(kind, features), facts))

    /** Действие, которому хватает любого из признаков. */
    private fun needs(vararg any: Feature) = object : Capability {
        override val id = CapabilityId("needs-" + any.joinToString { it.name })
        override val icon = "x"
        override fun label(state: ObjectState) = id.value
        override fun accepts(state: ObjectState) = any.any(state::has)
        override fun produces(state: ObjectState) = state
    }

    /** Действие над самой вещью: ему хватает вида. */
    private val onImage = object : Capability {
        override val id = CapabilityId("on-image")
        override val icon = "x"
        override fun label(state: ObjectState) = "Сохранить"
        override fun accepts(state: ObjectState) = state.kind == ObjectKind.IMAGE
        override fun produces(state: ObjectState) = state
    }

    @Test
    fun `call continues the found number and names it as the knowledge row shows it`() {
        val facts = mapOf(META_ENTITY_PHONE to phone)
        val because = becauseOf(needs(Feature.HAS_PHONE), graph(features = setOf(Feature.HAS_PHONE), facts = facts))

        assertEquals(Because(META_ENTITY_PHONE, "Телефон", shownKnowledge(META_ENTITY_PHONE, phone, facts)), because)
    }

    @Test
    fun `an action the thing itself carries continues the thing even when values were found`() {
        val g = graph(features = setOf(Feature.HAS_PHONE, Feature.HAS_ADDRESS), facts = mapOf(META_ENTITY_PHONE to phone))

        assertNull(becauseOf(onImage, g))
    }

    @Test
    fun `nothing found means nothing to continue but the thing`() {
        assertNull(becauseOf(needs(Feature.HAS_PHONE), graph()))
    }

    @Test
    fun `an action that any single value is enough for names the first of them`() {
        val g = graph(
            features = setOf(Feature.HAS_EMAIL, Feature.HAS_PHONE),
            facts = mapOf(META_ENTITY_PHONE to phone, META_ENTITY_PREFIX + "email" to "a@b.ua"),
        )

        assertEquals(META_ENTITY_PHONE, becauseOf(needs(Feature.HAS_EMAIL, Feature.HAS_PHONE), g)?.key)
    }

    @Test
    fun `an action that needs several values together is about the whole thing`() {
        val g = graph(
            kind = ObjectKind.TEXT,
            features = setOf(Feature.HAS_PHONE, Feature.HAS_EMAIL),
            facts = mapOf(META_ENTITY_PHONE to phone, META_ENTITY_PREFIX + "email" to "a@b.ua"),
        )

        assertNull(becauseOf(ExtractAllCapability(), g))
    }

    @Test
    fun `the meaning of the whole thing comes before one of its dates`() {
        val g = graph(
            features = setOf(Feature.HAS_DATE, Feature.IS_MEETING),
            facts = mapOf(META_ENTITY_PREFIX + "date" to "12.03.2027", META_SEMANTIC_SUMMARY to "Созвон с Анной"),
        )

        assertEquals(
            Because(META_SEMANTIC_TYPE, "Встреча", "Созвон с Анной"),
            becauseOf(needs(Feature.HAS_DATE, Feature.IS_MEETING), g),
        )
    }

    @Test
    fun `a recipe is the reason for a shopping list`() {
        val g = graph(features = setOf(Feature.IS_RECIPE), facts = mapOf(META_SEMANTIC_SUMMARY to "Борщ"))

        assertEquals(Because(META_SEMANTIC_TYPE, "Рецепт", "Борщ"), becauseOf(ShoppingListCapability(aiKeysReady), g))
    }

    @Test
    fun `fixing reading errors is about all the knowledge, not the first number found`() {
        val g = graph(features = setOf(Feature.HAS_PHONE), facts = mapOf(META_ENTITY_PHONE to phone))

        assertNull(becauseOf(FixErrorsCapability(aiKeysReady), g))
    }

    @Test
    fun `read text is the content of the thing, not a value in it`() {
        val g = graph(features = setOf(Feature.HAS_TEXT, Feature.HAS_PHONE), facts = mapOf(META_ENTITY_PHONE to phone))

        assertNull(becauseOf(TranslateCapability(aiKeysReady), g))
    }

    @Test
    fun `a found number is itself the value its actions continue`() {
        val node = graph(kind = KIND_PHONE, features = setOf(Feature.HAS_PHONE), facts = mapOf(META_ENTITY_PHONE to phone))

        assertNull(becauseOf(needs(Feature.HAS_PHONE), node))
    }

    @Test
    fun `a link is named without its scheme`() {
        val g = graph(features = setOf(Feature.HAS_URL), facts = mapOf(META_ENTITY_URL to "https://www.example.com/"))

        assertEquals(Because(META_ENTITY_URL, "Ссылка", "example.com"), becauseOf(needs(Feature.HAS_URL), g))
    }

    @Test
    fun `a code whose content already lies as a link is named without a value`() {
        val g = graph(
            features = setOf(Feature.HAS_QR, Feature.HAS_URL),
            facts = mapOf(META_ENTITY_URL to "https://point.leerio.app/health"),
        )

        assertEquals(Because(META_ENTITY_PREFIX + "qr", "QR-код", null), becauseOf(needs(Feature.HAS_QR), g))
    }
}
