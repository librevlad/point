package com.point.core.flow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * «Построить маршрут» ведёт к получателю (#772, #1176).
 *
 * Судья полей выбирает место по уликам страницы, и два отделения наклейки для него
 * одинаковы: побеждает первое, а им оказывается склад отправления. Чьё какое прочтение —
 * такая же улика, как форма и опора в словах страницы, и судья спрашивает её вместе с
 * остальными: отдельной правки поверх готового значения в обход воронки нет.
 *
 * Частного правила «место получателя» тоже нет: значение выбирает общая связь «прочтение
 * при своей стороне». Разрез настоящей наклейки на столбцы проверен в `BelongingTest`;
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
            atom("RA123456785UA", 100f, 220f, 450f, 260f),
            atom("RA123456780UA", 600f, 220f, 950f, 260f),
        ),
    )

    private fun onPage(text: String) = FieldCandidate(text, label.findOnPage(text).single().ids)

    private val senderBranch = onPage("Відділення №14")

    private val receiverBranch = onPage("Відділення №7")

    private val roles = mapOf(
        META_GRAPH_ROLE_PREFIX + "sender" to "Тарасенко",
        META_GRAPH_ROLE_PREFIX + "receiver" to "Лумброван",
    )

    private fun judge(
        readings: Map<String, List<FieldCandidate>>,
        withParties: Boolean = true,
    ): Map<String, JudgedField> = judgeFields(
        readings,
        label,
        label.text,
        if (withParties) label.belongings(readings, roles) else emptyMap(),
    ).won

    private val places = mapOf(META_ENTITY_PLACE to listOf(senderBranch, receiverBranch))

    @Test
    fun `место отправителя уступает месту получателя`() {
        assertEquals(receiverBranch.text, judge(places)[META_ENTITY_PLACE]?.text)
    }

    @Test
    fun `прежний выбор остаётся среди прочтений`() {
        val candidates = judge(places)[META_ENTITY_PLACE]?.candidates.orEmpty()

        assertTrue(senderBranch.text in candidates && receiverBranch.text in candidates)
    }

    @Test
    fun `сторон не назвали — значение выбирают одни улики страницы`() {
        assertEquals(senderBranch.text, judge(places, withParties = false)[META_ENTITY_PLACE]?.text)
    }

    /**
     * Слово стороны — не обход заземления (#809): выбранное прочтение приходит из той же
     * воронки, что и любое другое, значением становится слово страницы, а не слово модели,
     * и «прочитано» сказано по праву.
     */
    @Test
    fun `значение стороны — слово страницы, а не слово модели`() {
        val rewritten = FieldCandidate("м. Одеса, відділення №7", receiverBranch.ids)

        val judged = judge(mapOf(META_ENTITY_PLACE to listOf(senderBranch, rewritten)))

        assertEquals(receiverBranch.text, judged[META_ENTITY_PLACE]?.text)
        assertTrue("значение не опёрто на слова страницы", judged[META_ENTITY_PLACE]?.grounded == true)
    }

    /**
     * Хозяин — часть решения судьи, а не догадка о нём после (#1176): текст прочтения к этому
     * времени уже переписан словом страницы, и узнать по нему выбранное прочтение нельзя.
     */
    @Test
    fun `выбранное прочтение приносит и свою сторону`() {
        val rewritten = FieldCandidate("м. Одеса, відділення №7", receiverBranch.ids)

        val judged = judge(mapOf(META_ENTITY_PLACE to listOf(senderBranch, rewritten)))

        assertEquals(META_GRAPH_ROLE_PREFIX + "receiver", judged[META_ENTITY_PLACE]?.owner?.partyKey)
        assertEquals(receiverBranch.text, judged[META_ENTITY_PLACE]?.owner?.reading?.text)
    }

    /** Подпись значения едет с выбранным прочтением, а не остаётся от проигравшего (#782). */
    @Test
    fun `подпись значения — от прочтения стороны`() {
        val receiverLine = "Отримувач Лумброван, Відділення №7"
        val signed = mapOf(
            META_ENTITY_PLACE to listOf(
                senderBranch.copy(line = "Відправник Тарасенко, Відділення №14"),
                receiverBranch.copy(line = receiverLine),
            ),
        )

        assertEquals(receiverLine, judge(signed)[META_ENTITY_PLACE]?.line)
    }

    /** Сторона решает спор годных прочтений, а забракованное не воскрешает (#809, #1122). */
    @Test
    fun `забракованное прочтение не становится значением из-за стороны`() {
        val good = onPage("RA123456785UA")
        val badCheckDigit = onPage("RA123456780UA")

        val judged = judge(mapOf(META_ENTITY_TRACK to listOf(good, badCheckDigit)))

        assertEquals(good.text, judged[META_ENTITY_TRACK]?.text)
    }
}
