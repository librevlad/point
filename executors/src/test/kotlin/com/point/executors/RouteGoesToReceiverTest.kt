package com.point.executors

import com.point.core.flow.Atom
import com.point.core.flow.AtomLayer
import com.point.core.flow.Box
import com.point.core.flow.EvidenceClass
import com.point.core.flow.FieldCandidate
import com.point.core.flow.META_ENTITY_PLACE
import com.point.core.flow.META_GRAPH_ROLE_PREFIX
import com.point.core.flow.findOnPage
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * «Построить маршрут» ведёт к получателю (#772).
 *
 * Судья полей выбирает место по уликам страницы, и два отделения наклейки для него
 * одинаковы: побеждает первое, а им оказывается склад отправления. Роль получателя
 * разбирается позже, из того же ответа модели, — здесь проверяется, что эти два знания
 * встречаются и маршрут разворачивается туда, куда едет посылка.
 *
 * Разрез страницы на столбцы проверен на настоящей наклейке в `PlaceOfReceiverTest`;
 * здесь важна только сшивка, поэтому раскладка простая.
 */
class RouteGoesToReceiverTest {

    private var next = 0

    private fun atom(text: String, left: Float, top: Float, right: Float, bottom: Float) =
        Atom(id = "a${next++}", text = text, box = Box(left, top, right, bottom))

    private val label = AtomLayer(
        listOf(
            atom("Тарасенко", 100f, 100f, 450f, 140f),
            atom("Лумброван", 600f, 100f, 950f, 140f),
            atom("Відділення №14", 100f, 160f, 450f, 200f),
            atom("Відділення №7", 600f, 160f, 950f, 200f),
        ),
    )

    private fun place(text: String) = FieldCandidate(text, label.findOnPage(text).single().ids)

    private val senderBranch = place("Відділення №14")

    private val receiverBranch = place("Відділення №7")

    private val candidates = mapOf(META_ENTITY_PLACE to listOf(senderBranch, receiverBranch))

    private val receiver = mapOf(META_GRAPH_ROLE_PREFIX + "receiver" to "Лумброван")

    private fun judged(text: String) = mapOf(
        META_ENTITY_PLACE to JudgedField(
            text = text,
            evidence = setOf(EvidenceClass.SEMANTIC),
            grounded = true,
            candidates = listOf(text),
        ),
    )

    @Test
    fun `место отправителя уступает месту получателя`() {
        val fixed = withPlaceOfReceiver(judged(senderBranch.text), candidates, receiver, label)

        assertEquals(receiverBranch.text, fixed[META_ENTITY_PLACE]?.text)
    }

    @Test
    fun `прежний выбор остаётся среди прочтений`() {
        val fixed = withPlaceOfReceiver(judged(senderBranch.text), candidates, receiver, label)

        assertEquals(
            listOf(receiverBranch.text, senderBranch.text),
            fixed[META_ENTITY_PLACE]?.candidates,
        )
    }

    @Test
    fun `выбранное судьёй место получателя не переписывается`() {
        val already = judged(receiverBranch.text)

        assertEquals(already, withPlaceOfReceiver(already, candidates, receiver, label))
    }

    @Test
    fun `без слоя слов ничего не меняется`() {
        val was = judged(senderBranch.text)

        assertEquals(was, withPlaceOfReceiver(was, candidates, receiver, layer = null))
    }

    @Test
    fun `роль получателя не названа — место остаётся прежним`() {
        val was = judged(senderBranch.text)

        assertEquals(was, withPlaceOfReceiver(was, candidates, roles = emptyMap(), layer = label))
    }

    @Test
    fun `места среди полей нет — правило молчит`() {
        assertEquals(emptyMap<String, JudgedField>(), withPlaceOfReceiver(emptyMap(), candidates, receiver, label))
    }
}
