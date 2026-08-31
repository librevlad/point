package com.point.core.flow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Страна номера не додумывается ни показом, ни знанием (#1029).
 *
 * Чек из Оклахомы: в графе лежал честный `918-682-1551` и никакой страны, а на экране стояло
 * «+49 9186 821551». Правило «страна — только когда она одна» соблюдал разбор знания, а показ
 * шёл мимо и брал первую подошедшую страну из списка подсказок. Один и тот же чек читался
 * немецким на телефоне владельца и американским на эмуляторе — по стране устройства, а не по
 * документу.
 *
 * Само правило «когда она одна» оказалось той же догадкой с другого конца: список подсказок
 * начинается со страны устройства, а для записи без разделителей он ею и заканчивается. Тот
 * же чек, прочитанный как `918 682 1551`, выходил американским на американском телефоне,
 * немецким на немецком и британским на британском.
 *
 * Решение владельца 21.08.2026: не додумывать страну. Номер без кода страны печатается как в
 * документе; код дописывается только когда страна известна из самого документа.
 */
class PhoneCountryIsNotGuessedTest {

    /** Номер с чека FAMILY DOLLAR, Muskogee, OK: годится и Америке, и Германии. */
    private val fromOklahoma = "918-682-1551"

    /**
     * Устройства, на которых читается один и тот же документ.
     *
     * Две последние — не из списка близких стран (`NEARBY`), и это важно: страна устройства
     * попадает в разбор всегда, поэтому правило, проверенное только на странах из списка, не
     * проверено вовсе.
     */
    private val devices = listOf("UA", "US", "DE", "PL", "GB", "FR")

    /**
     * Записи, чью страну документ не назвал, а прежний код называл сам.
     *
     * Тире, скобки и ведущий ноль — знак того, что текст написан как номер, и такой текст
     * искали сразу по нескольким странам. Без них искали ровно в одной — в стране
     * устройства, — и она же оказывалась «единственной подошедшей», то есть страной номера.
     * Но и там, где стран пробовали несколько, подойти могла одна: `050 111 22 33` подходило
     * только Украине. Оба случая — одна и та же догадка, и здесь они проверяются вместе.
     */
    private val countryNotWritten = listOf("918 682 1551", "9186821551", "22 123 45 67", "050 111 22 33")

    @Test
    fun `номер без кода страны показывается как в документе`() {
        assertEquals(fromOklahoma, PhoneNumbers.shown(fromOklahoma, "UA"))
    }

    @Test
    fun `один документ читается на всех устройствах одинаково`() {
        val everywhere = devices.map { PhoneNumbers.shown(fromOklahoma, it) }

        assertEquals("страна показа зависит от устройства-$everywhere", 1, everywhere.distinct().size)
    }

    /**
     * Запись без кода страны — тот же случай, и раньше он был худшим из всех.
     *
     * `918 682 1551` становился «(918) 682-1551» на американском устройстве, «09186 821551» на
     * немецком и «0918 682 1551» на британском: страна не просто угадана — она угадана по
     * телефону человека, и на каждом телефоне своя. `22 123 45 67` был польским в Польше,
     * немецким в Германии и французским во Франции.
     */
    @Test
    fun `запись без кода страны не берёт страну у устройства`() {
        countryNotWritten.forEach { text ->
            devices.forEach { device ->
                assertEquals(
                    "номер разобран страной устройства-$device-$text",
                    text,
                    PhoneNumbers.shown(text, device),
                )
            }
        }
    }

    @Test
    fun `страна не приписывается номеру, у которого её нет`() {
        val guessed = listOf("+49", "+1", "+380", "+48")

        (listOf(fromOklahoma) + countryNotWritten).forEach { text ->
            devices.forEach { device ->
                guessed.forEach {
                    assertTrue(
                        "номеру дописан код страны-$it-$text-$device",
                        !PhoneNumbers.shown(text, device).contains(it),
                    )
                }
            }
        }
    }

    @Test
    fun `названный в документе код остаётся на экране`() {
        assertTrue(PhoneNumbers.shown("+1 918-682-1551", "UA").startsWith("+1"))
        assertTrue(PhoneNumbers.shown("+380676360560", "PL").startsWith("+380"))
    }

    /** Известную страну устройство по-прежнему вправе не называть: свой код человек знает. */
    @Test
    fun `свой номер остаётся домашним`() {
        assertEquals("067 636 0560", PhoneNumbers.shown("+380676360560", "UA"))
    }

    @Test
    fun `строка знания не приписывает номеру чужую страну`() {
        val row = shownKnowledge(META_ENTITY_PHONE, fromOklahoma, emptyMap(), region = "UA")

        assertEquals(fromOklahoma, row)
    }

    /**
     * То же на пути человека и целиком: строка знания, собранная из графа.
     *
     * Проверяется не отдельная функция, а то, что стоит в строке экрана: номер, страна словом
     * и вид — так, как их соберут `mergeKnowledge` и `knowledgeRows` на любом пути, приносящем
     * знание об объекте. Один и тот же документ на шести устройствах даёт одну строку, и в ней
     * ровно то, что написано в документе.
     */
    @Test
    fun `строка знания о номере одинакова на любом устройстве`() {
        (listOf(fromOklahoma) + countryNotWritten).forEach { text ->
            val rows = devices.map { device ->
                val graph = mergeKnowledge(emptyMap(), mapOf(META_ENTITY_PHONE to text), region = device)
                shownKnowledge(META_ENTITY_PHONE, text, graph, device)
            }

            assertEquals("строка номера зависит от устройства-$text-$rows", 1, rows.distinct().size)
            assertEquals(text, rows.first())
        }
    }

    /**
     * Знание о стране в графе — тоже не свойство устройства.
     *
     * Экран берёт страну и вид из графа (`entity.phone.country`, `entity.phone.kind`), и если
     * туда попала догадка, она выйдет к человеку первым же новым спрашивающим.
     */
    @Test
    fun `страна в графе не зависит от того, где стоит устройство`() {
        (listOf(fromOklahoma) + countryNotWritten).forEach { text ->
            devices.forEach { device ->
                val graph = mergeKnowledge(emptyMap(), mapOf(META_ENTITY_PHONE to text), region = device)

                assertNull(
                    "страна выдумана на устройстве-$device-$text-${graph["$META_ENTITY_PHONE.country"]}",
                    graph["$META_ENTITY_PHONE.country"],
                )
            }
        }
    }

    /**
     * Тот же номер, прочитанный дважды — с кодом и без, — остаётся одним знанием везде.
     *
     * Тождество считалось по E.164, а код страны для записи без `+` брался у первой
     * подошедшей страны. На украинском телефоне `918-682-1551` становился немецким, а
     * `+1 918-682-1551` рядом — американским: человек видел спор «или:» на месте одного
     * номера. На американском телефоне тот же чек давал одну строку.
     */
    @Test
    fun `один номер в двух записях не становится спором ни на одном устройстве`() {
        devices.forEach { device ->
            val merged = mergeFacts(
                mapOf(META_ENTITY_PHONE to "+1 918-682-1551"),
                mapOf(META_ENTITY_PHONE to fromOklahoma),
                device,
            )

            assertNull(
                "на устройстве-$device один номер показан спором-${merged[META_ENTITY_PHONE + META_ALT_SUFFIX]}",
                merged[META_ENTITY_PHONE + META_ALT_SUFFIX],
            )
        }
    }

    @Test
    fun `тождество номера не спрашивает, где стоит устройство`() {
        val pairs = listOf(
            "+1 918-682-1551" to fromOklahoma,
            "067 636 05 60" to "+380676360560",
            "+380676360560" to "+380501112233",
            fromOklahoma to "(918) 682-1551",
        )

        pairs.forEach { (left, right) ->
            val answers = devices.map { sameFact(META_ENTITY_PHONE, left, right, it) }

            assertEquals(
                "«одно ли это знание» зависит от устройства-$left-$right-$answers",
                1,
                answers.distinct().size,
            )
        }
    }

    /**
     * Что устройство в тождестве всё-таки решает — и ровно это, не больше (#936).
     *
     * Сравнивает записи библиотека, и страну она не подсказывает. Но прежде сравнения обе
     * записи должны быть номерами, а «номер ли это вообще» решает тот же отбор, что решает,
     * попадёт ли текст в граф фактом: `918 682 1551` — номер в Америке и не номер в Украине.
     * Там, где текст номером не считается, нет и факта, о тождестве которого спрашивать.
     *
     * Правило проверяется как правило: среди устройств, где обе записи прошли отбор, ответ
     * один — и это ответ про сами номера, а не про место, где стоит телефон.
     */
    @Test
    fun `тождество расходится только там, где текст не считается номером`() {
        val pairs = listOf(
            "918 682 1551" to "+1 918-682-1551",
            "9186821551" to fromOklahoma,
            "22 123 45 67" to "+48221234567",
            "067 636 05 60" to "+380676360560",
        )

        pairs.forEach { (left, right) ->
            val asked = devices.filter { PhoneNumbers.exists(left, it) && PhoneNumbers.exists(right, it) }
            val answers = asked.map { sameFact(META_ENTITY_PHONE, left, right, it) }

            assertTrue("отбор не пропустил пару ни на одном устройстве-$left-$right", asked.isNotEmpty())
            assertEquals(
                "«одно ли это знание» зависит от устройства-$left-$right-$asked-$answers",
                1,
                answers.distinct().size,
            )
        }
    }

    /**
     * Вид номера — тоже свойство страны, и брался он у первой подошедшей, как и формат.
     *
     * На экран вид попадал только вместе со страной: `withPhoneKnowledge` спрашивает его
     * после того, как страна названа, — поэтому угаданный вид человек не видел. Но угадывал
     * его сам [PhoneNumbers.kind], и первый же новый спрашивающий вынес бы догадку на экран.
     * Правило проверяется как правило, а не как один вызов: вид назван — значит страна
     * известна, для любого номера.
     */
    @Test
    fun `вид номера не угадывается по стране устройства`() {
        assertNull("вид назван по угаданной стране", PhoneNumbers.kind(fromOklahoma))
        countryNotWritten.forEach {
            assertNull("вид назван по угаданной стране-$it", PhoneNumbers.kind(it))
        }
    }

    @Test
    fun `вид называется только там, где названа страна`() {
        val numbers = listOf(
            fromOklahoma, "+1 918-682-1551", "067 636 05 60", "+380676360560",
            "067 123 45 67", "+48221234567", "0932423759",
        ) + countryNotWritten

        numbers.filter { PhoneNumbers.kind(it) != null }.forEach { named ->
            assertNotNull(
                "вид назван без страны-$named-${PhoneNumbers.kind(named)}",
                PhoneNumbers.country(named),
            )
        }
    }

    /**
     * Известную страну устройство вправе не повторять (#932), но подменить её не может:
     * на любом устройстве это тот же украинский номер, а не чужой.
     */
    @Test
    fun `устройство не меняет страну известного номера`() {
        val ukrainian = "+380676360560"

        devices.forEach { device ->
            val shown = PhoneNumbers.shown(ukrainian, device)

            assertEquals("страна номера изменилась-$device", "UA", PhoneNumbers.country(ukrainian))
            assertTrue(
                "на устройстве-$device дописан чужой код-$shown",
                !shown.contains("+") || shown.startsWith("+380"),
            )
            assertTrue(
                "на устройстве-$device показан другой номер-$shown",
                PhoneNumbers.same(shown, ukrainian, device),
            )
        }
    }

    /**
     * Что устройству на экране всё ещё позволено — и ровно это, не больше (#932).
     *
     * Своя страна не называется: человек знает, где живёт, и свой код в номере ему не нужен.
     * Украинский номер стоит на украинском устройстве строкой без `+380`, а на польском — с
     * ним и со словом-страной. Знание при этом одно и то же: страна номера `UA` на обоих, и
     * обе строки — один и тот же номер. Устройство сокращает показ уже известного, но ничего
     * не дописывает и ничего не подменяет.
     */
    @Test
    fun `устройство сокращает показ известного, но не меняет знание`() {
        val ukrainian = "+380676360560"

        val rows = listOf("UA", "PL").map { device ->
            val graph = mergeKnowledge(emptyMap(), mapOf(META_ENTITY_PHONE to ukrainian), region = device)

            assertEquals(
                "страна номера изменилась на устройстве-$device",
                "UA",
                graph["$META_ENTITY_PHONE.country"],
            )
            shownKnowledge(META_ENTITY_PHONE, ukrainian, graph, device).substringBefore(" ·")
        }

        assertTrue("дома повторён свой код-${rows[0]}", !rows[0].contains("+380"))
        assertTrue("за границей код страны потерян-${rows[1]}", rows[1].contains("+380"))
        assertTrue("устройства показали разные номера-$rows", PhoneNumbers.same(rows[0], rows[1], "UA"))
    }

    /**
     * Принятая цена решения — на пути человека и с открытыми глазами (#1294).
     *
     * Номер с кадра из #932 отбор пропускает, но пропускает **подсказкой** устройства (#936),
     * а подсказка страной номера не является. Значит группировки у Point для него нет: цифры
     * стоят там, где их прочитали, и кода страны рядом не появляется.
     *
     * Следы чтения при этом убираются — скобка без пары, двойной пробел, пробел у тире
     * (решение владельца 31.08.2026 по #1294): страны они не требуют. Тест стоит здесь, чтобы
     * цена падала вместе с решением, а не расходилась молча с текстом рядом: пока правило
     * живо, ни одна цифра не двигается и страна не выдумывается.
     */
    @Test
    fun `номер с кадра без кода страны показывается без страны и без перестановки цифр`() {
        val fromFrame = "06 1 ) 2 80-44-2 1"

        assertTrue("отбор не пропустил номер с кадра", PhoneNumbers.exists(fromFrame, "UA"))
        devices.forEach { device ->
            val graph = mergeKnowledge(emptyMap(), mapOf(META_ENTITY_PHONE to fromFrame), region = device)
            val shown = shownKnowledge(META_ENTITY_PHONE, fromFrame, graph, device)

            assertEquals(
                "цифры переставлены или потеряны на устройстве-$device",
                fromFrame.filter(Char::isDigit),
                shown.filter(Char::isDigit),
            )
            assertFalse("страна выдумана на устройстве-$device: $shown", shown.contains("+"))
            assertFalse("след чтения остался на устройстве-$device: $shown", shown.contains(")"))
        }
    }

    /**
     * Вторая грань той же цены, и платит за неё человек потерей номера (#1303).
     *
     * Тождество считает библиотека, и когда код страны назвала только одна запись, она
     * перечитывает вторую **в стране первой**, снимая её национальный ноль. Тем же способом
     * одним знанием становятся не только три записи одного номера, но и два разных: запись
     * без кода `067 636 05 60` — «то же самое» и для украинского `+380676360560`, и для
     * настоящего немецкого `+49 6763 60560`, хотя между собой эти двое разные. Слияние
     * оставляет пришедшего первым, снимает спор — и второго номера не остаётся нигде: ни
     * строкой, ни `.alt`, ни `.more`.
     *
     * До решения по #1029 эти двое расходились по угаданной стране и жили спором на пяти
     * устройствах из шести — а на немецком сливались и тогда (замер на коде `main`,
     * 25.08.2026). То есть догадка не спасала, а делала потерю зависящей от того, где стоит
     * телефон. Развести их честно нечем: назвать страну записи без кода и значит угадать.
     *
     * Тест стоит здесь, чтобы цена падала вместе с решением, а не жила одним текстом в
     * описании: пока правило живо, второй номер обязан исчезать, и видно это на пути
     * человека — в графе и в строке экрана.
     */
    @Test
    fun `два разных номера сливаются в один, и один исчезает`() {
        val fromDocument = "067 636 05 60"
        val ukrainian = "+380676360560"
        val german = "+49 6763 60560"

        assertTrue("это один и тот же номер", !PhoneNumbers.same(ukrainian, german, "UA"))

        devices.forEach { device ->
            assertTrue(
                "своя запись перестала быть тем же номером-$device",
                PhoneNumbers.same(fromDocument, ukrainian, device),
            )
            assertTrue(
                "чужой номер отличён от записи без кода-$device",
                PhoneNumbers.same(fromDocument, german, device),
            )

            listOf(german to fromDocument, fromDocument to german).forEach { (first, second) ->
                val graph = mergeKnowledge(
                    mapOf(META_ENTITY_PHONE to first),
                    mapOf(META_ENTITY_PHONE to second),
                    region = device,
                )

                assertEquals(
                    "выжил не первый прочитанный-$device-$graph",
                    first,
                    graph[META_ENTITY_PHONE],
                )
                assertNull(
                    "спор о номере сохранён-$device-${graph[META_ENTITY_PHONE + META_ALT_SUFFIX]}",
                    graph[META_ENTITY_PHONE + META_ALT_SUFFIX],
                )
                assertTrue("второй номер уцелел-$device-$graph", graph.values.none { it == second })
            }

            val graph = mergeKnowledge(
                mapOf(META_ENTITY_PHONE to german),
                mapOf(META_ENTITY_PHONE to fromDocument),
                region = device,
            )
            val row = shownKnowledge(META_ENTITY_PHONE, graph.getValue(META_ENTITY_PHONE), graph, device)

            assertTrue("номер из документа остался на экране-$device-$row", !row.contains("636 05 60"))
            assertTrue(
                "на экране не тот номер, что уцелел-$device-$row",
                PhoneNumbers.same(row.substringBefore(" ·"), german, device),
            )
        }
    }

    /** Показ и знание отвечают одно и то же: страны, которой нет в графе, нет и на экране. */
    @Test
    fun `показ и знание об одной стране согласны`() {
        val numbers = listOf(fromOklahoma, "+1 918-682-1551", "067 123 45 67", "+380676360560") +
            countryNotWritten

        numbers.forEach { text ->
            devices.forEach { device ->
                val known = PhoneNumbers.country(text) != null
                val shownAsRead = PhoneNumbers.shown(text, device) == text

                assertTrue("экран знает про страну больше графа-$text-$device", known || shownAsRead)
            }
        }
    }
}
