package com.point.executors

import com.point.core.flow.GraphState
import com.point.core.flow.META_ENTITY_ADDRESS
import com.point.core.flow.META_ENTITY_PHONE
import com.point.core.flow.META_ENTITY_URL
import com.point.core.flow.understoodName
import com.point.core.flow.capabilities.OcrCapability
import com.point.core.model.Feature
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Действия визитки знают, что они продолжают (вещь → смыслы → естественные шаги).
 *
 * Экран раскладывает действия по найденному: «Позвонить» — к номеру, «Открыть на карте» — к
 * адресу, а «Поделиться» — к самой визитке. Решает это не список «какое действие к какому
 * значению», а то, предлагается ли действие этой же вещи без найденного.
 */
class ActionsFollowWhatWasFoundTest {

    private val registry = DefaultCapabilityRegistry(
        capabilities = setOf(
            CallCapability(),
            SmsCapability(),
            MapCapability(),
            SaveContactCapability(),
            OpenUrlCapability(),
            ShareCapability(),
            SaveCapability(),
            OcrCapability(),
        ),
        policy = DefaultBubblePolicy(),
    )

    private val card = PointObject(
        id = "card",
        mime = "image/jpeg",
        uri = ScratchRef("/scratch/card.jpg"),
        state = ObjectState(
            ObjectKind.IMAGE,
            setOf(Feature.HAS_TEXT, Feature.HAS_PHONE, Feature.HAS_ADDRESS, Feature.HAS_URL),
        ),
        metadata = mapOf(
            META_ENTITY_PHONE to "+380671234567",
            META_ENTITY_ADDRESS to "Київ, вулиця Ярославська, 14",
            META_ENTITY_URL to "https://tihiy-dvor.example/",
        ),
    )

    private val bubbles = registry.bubblesFor(GraphState(card)).associateBy { it.capabilityId }

    @Test
    fun `позвонить, написать и сохранить контакт продолжают номер`() {
        listOf(CallCapability.ID, SmsCapability.ID, SaveContactCapability.ID).forEach { id ->
            assertEquals("$id", META_ENTITY_PHONE, bubbles.getValue(id).because?.key)
        }
        assertEquals(understoodName(META_ENTITY_PHONE), bubbles.getValue(CallCapability.ID).because?.name)
    }

    @Test
    fun `карта продолжает адрес, а ссылка открывается со своим адресом`() {
        assertEquals(META_ENTITY_ADDRESS, bubbles.getValue(MapCapability.ID).because?.key)
        assertEquals("tihiy-dvor.example", bubbles.getValue(OpenUrlCapability.ID).because?.value)
    }

    @Test
    fun `поделиться, сохранить и прочитать — о самой визитке`() {
        assertNull(bubbles.getValue(ShareCapability.ID).because)
        assertNull(bubbles.getValue(SaveCapability.ID).because)
        assertNull(bubbles.getValue(OcrCapability.ID).because)
    }
}
