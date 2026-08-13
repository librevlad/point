package com.point

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import com.point.core.flow.KIND_ADDRESS
import com.point.core.flow.KIND_EMAIL
import com.point.core.flow.KIND_IDENTIFIER
import com.point.core.flow.KIND_PHONE
import com.point.core.flow.META_ENTITY_PREFIX
import com.point.core.flow.META_ENTITY_TRACK
import com.point.core.flow.META_GRAPH_ROLE_PREFIX
import com.point.core.model.Bubble
import com.point.core.model.CapabilityId
import com.point.core.model.Feature
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import com.point.core.model.ValueRef
import com.point.core.ui.FirstScreen
import com.point.core.ui.theme.PointTheme
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class OneFactOnePlaceTest {

    @get:Rule val compose = createComposeRule()

    private val phone = "+380 67 123 45 67"

    /**
     * Так номер выходит на экран: разобранным библиотекой, а не как записан в документе (#932).
     * Вид зависит от страны устройства — украинский номер украинцу показывается местным, —
     * поэтому страна названа прямо, а не берётся из окружения теста.
     */
    private val phoneShown = "067 123 4567"

    @Before fun ukrainianDevice() {
        com.point.core.flow.PhoneNumbers.region = "UA"
    }
    private val email = "olena@tihiy-dvor.example"
    private val address = "Київ, вулиця Ярославська, 14"

    private val card = PointObject(
        id = "card",
        mime = "image/jpeg",
        uri = ScratchRef("/scratch/card.jpg"),
        state = ObjectState(
            ObjectKind.IMAGE,
            setOf(Feature.HAS_PHONE, Feature.HAS_EMAIL, Feature.HAS_ADDRESS),
        ),
        metadata = mapOf(
            "name" to "vizitka.jpg",
            META_ENTITY_PREFIX + "phone" to phone,
            META_ENTITY_PREFIX + "email" to email,
            META_ENTITY_PREFIX + "address" to address,
        ),
    )

    private fun found(id: String, kind: ObjectKind, value: String, feature: Feature? = null) = PointObject(
        id = id,
        mime = "text/plain",
        uri = ValueRef(value),
        state = ObjectState(kind, setOfNotNull(feature)),
    )

    private val cardFound = listOf(
        found("card:phone", KIND_PHONE, phone, Feature.HAS_PHONE),
        found("card:email", KIND_EMAIL, email, Feature.HAS_EMAIL),
        found("card:address", KIND_ADDRESS, address, Feature.HAS_ADDRESS),
    )

    private val saveContact = Bubble(
        icon = "contact",
        title = "Сохранить контакт",
        capabilityId = CapabilityId("save-contact"),
        expectedNextState = ObjectState(ObjectKind.TEXT),
    )

    private fun screen(obj: PointObject, found: List<PointObject> = emptyList()) {
        compose.setContent {
            PointTheme(darkTheme = true) {
                FirstScreen(obj = obj, bubbles = listOf(saveContact), onBubble = {}, found = found)
            }
        }
    }

    private fun timesOnScreen(text: String) =
        compose.onAllNodesWithText(text).fetchSemanticsNodes().size

    @Test fun `на визитке телефон виден ровно один раз`() {
        screen(card, cardFound)

        assertEquals("номер написан на экране дважды — ровно то, что увидел владелец", 1, timesOnScreen(phone))
    }

    // #696 (охота 2026-08-10): владелец видел на визитке телефон, почту и адрес —
    // три значения, — а заголовок над списком говорил «Нашёл · 2» (телефон уехал
    // в строку «Сохранить контакт» и в счёт не попадал). Решение владельца:
    // «Без числа, когда часть выше» — там, где знание разнесено по экрану, число
    // не врёт, потому что его просто нет.
    // Решение владельца 11.08.2026: найденное живёт внизу одним списком, действия — внутри
    // объекта. Раз ничего не уезжает наверх строкой действия, число снова честное и нужное.
    @Test fun `всё найденное в одном списке — число говорит правду`() {
        screen(card, cardFound)

        compose.onNodeWithText("Нашёл · 3").assertExists()
    }

    @Test fun `факты без готового действия остаются на месте — почта и адрес никуда не делись`() {
        screen(card, cardFound)

        compose.onNodeWithText(email).assertExists()
        compose.onNodeWithText(address).assertExists()
        compose.onNodeWithText("Почта", substring = true).assertExists()
        compose.onNodeWithText("Адрес", substring = true).assertExists()
    }

    @Test fun `неготовое действие ничего не прячет — найденное показано целиком`() {

        val parcel = PointObject(
            id = "parcel",
            mime = "image/png",
            uri = ScratchRef("/scratch/parcel.png"),
            state = ObjectState(ObjectKind.IMAGE),
            metadata = mapOf(META_GRAPH_ROLE_PREFIX + "carrier" to "Нова Пошта"),
        )

        screen(parcel, listOf(found("p:num", KIND_IDENTIFIER, "20 4514 9154 9395")))

        compose.onNodeWithText("Нашёл · 1").assertExists()
        compose.onNodeWithText("20 4514 9154 9395").assertExists()
    }

    @Test fun `правило общее — трек-номер посылки тоже показан один раз`() {

        val parcel = PointObject(
            id = "parcel",
            mime = "image/png",
            uri = ScratchRef("/scratch/parcel.png"),
            state = ObjectState(ObjectKind.IMAGE),
            metadata = mapOf(
                META_ENTITY_TRACK to "20 4514 9154 9395",
                META_GRAPH_ROLE_PREFIX + "carrier" to "Нова Пошта",
            ),
        )

        screen(parcel, listOf(found("p:num", KIND_IDENTIFIER, "20 4514 9154 9395")))

        assertEquals(1, timesOnScreen("20 4514 9154 9395"))
    }

    @Test fun `узла ещё нет — знание всё равно названо, и ровно один раз`() {

        screen(card)

        assertEquals(1, timesOnScreen(phoneShown))
        compose.onNodeWithText("Нашёл телефон").assertExists()
        compose.onNodeWithText("Нашёл почту").assertExists()
    }

    // Само значение объекта не рассказывается второй раз строкой знания: внутри телефона
    // «067 636 05 60» висело «Нашёл телефон 067 636 05 60» — заголовок и знание об одном.
    @Test fun `объект-значение не повторяет себя строкой знания`() {
        val number = found("card:phone", KIND_PHONE, phone, Feature.HAS_PHONE)
            .let { it.copy(metadata = mapOf(META_ENTITY_PREFIX + "phone" to phone)) }

        screen(number)

        assertEquals(1, timesOnScreen(phoneShown))
        compose.onNodeWithText("Нашёл телефон").assertDoesNotExist()
    }
}
