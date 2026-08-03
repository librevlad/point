package com.point.core.flow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Метрика таблиц #262: семь кадров корпуса — «извлечь таблицу», и мерить их можно только по
 * результату действия. Главное число — расхождения, прошедшие молча; предупреждённое расхождение
 * провалом не считается, а стена предупреждений считается.
 *
 * Прецедент — ведомость владельца (кадр 23): печатный бланк, ~35 строк × 8 колонок, артикул в
 * первой колонке, поверх печати синяя ручка.
 */
class TableMetricTest {

    private fun ledger(
        rows: Int,
        columns: Int = 3,
        header: Boolean = true,
        named: List<ExpectedRow>,
    ) = TableExpectation("23", rows, columns, keyColumn = 0, header = header, namedRows = named)

    private fun row(key: String, vararg cells: Pair<Int, String>) = ExpectedRow(key, cells.toMap())

    private val head = listOf("Артикул", "Наименование", "Кол-во")

    @Test
    fun `совпавшее значение совпало, а расхождение под меткой — цена проверки, не провал`() {
        val score = scoreTable(
            ledger(2, named = listOf(row("11004", 1 to "Гречка", 2 to "120"), row("11006", 2 to "40"))),
            listOf(
                head,
                listOf("11004", "Гречка", "12О⚠"), // латинская O вместо нуля, но модель предупредила
                listOf("11006", "Рис", "40"),
            ),
        )

        assertEquals(2, score.matchedCells)
        assertEquals(1, score.flagged.size)
        assertTrue("предупреждённое расхождение молчаливым не бывает", score.silent.isEmpty())
        assertTrue("человек предупреждён — значит действие выполнено", score.passed)
    }

    @Test
    fun `расхождение без метки — главное число и провал`() {
        val score = scoreTable(
            ledger(2, named = listOf(row("11004", 1 to "Гречка", 2 to "120"), row("11006", 2 to "40"))),
            listOf(
                head,
                listOf("11004", "Гречка", "125"), // 120 стало 125, и ничто об этом не сказало
                listOf("11006", "Рис", "40"),
            ),
        )

        assertEquals(1, score.silent.size)
        assertEquals(CellDiff("11004", 2, "120", "125"), score.silent.single())
        assertEquals(listOf(TableFailure.SILENT_CELLS), score.failures)
    }

    @Test
    fun `потерянная строка названа поимённо, а её значения считаются молчаливыми`() {
        val score = scoreTable(
            ledger(2, named = listOf(row("11004", 2 to "120"), row("11006", 1 to "Рис", 2 to "40"))),
            listOf(head, listOf("11004", "Гречка", "120")),
        )

        assertEquals(listOf("11004"), score.found)
        assertEquals(listOf("11006"), score.lost)
        assertEquals(2, score.silent.size)
        assertTrue("ячейки потерянной строки — не пустые, а отсутствующие", score.silent.all { it.actual == null })
        assertEquals(listOf(TableFailure.SILENT_CELLS, TableFailure.LOST_ROWS), score.failures)
    }

    @Test
    fun `строки узнаются по артикулу, даже когда колонка съехала`() {
        // Живой случай #294: одно чтение отдаёт артикул первой колонкой, другое — второй,
        // потому что первую заняла пустая колонка бланка. Значения ехать за ней не должны.
        val score = scoreTable(
            ledger(2, named = listOf(row("11004", 1 to "Гречка", 2 to "120"), row("11006", 1 to "Рис"))),
            listOf(
                listOf("", "Артикул", "Наименование", "Кол-во"),
                listOf("", "11004", "Гречка", "120"),
                listOf("", "11006", "Рис", "40"),
            ),
        )

        assertEquals(3, score.matchedCells)
        assertTrue(score.silent.isEmpty() && score.lost.isEmpty())
        // Лишняя колонка при этом не прощена — файл шире документа, и это сказано отдельно.
        assertEquals(listOf(TableFailure.WRONG_SHAPE), score.failures)
    }

    @Test
    fun `испорченный ключ — строка и потеряна, и лишняя`() {
        val score = scoreTable(
            ledger(2, named = listOf(row("11004", 2 to "120"), row("11006", 2 to "40"))),
            listOf(head, listOf("11004", "Гречка", "120"), listOf("11О06", "Рис", "40")),
        )

        assertEquals(listOf("11006"), score.lost)
        assertEquals(1, score.extra)
        assertTrue(TableFailure.EXTRA_ROWS in score.failures)
    }

    @Test
    fun `точка и скобка, прилипшие к ключу от вёрстки окна, — оформление, а не другой номер`() {
        // Дословные ключи ночного прогона кадра 06 (окно учётной программы) — вёрстку окна
        // у номера договора движок читает прилипшей точкой или скобкой, и метрика объявляла
        // пять строк потерянными и пять лишними на таблице, прочитанной начисто.
        val e = parseTableExpectation(
            "06",
            listOf(
                "строк 4",
                "колонок 2",
                "ключ 1",
                "шапка да",
                "--",
                "286/2/18/138\t18.10.18",
                "286/2/18/137\t18.10.18",
                "286/2/20/3\t13.02.20",
                "286/2/18/128\t11.10.18",
            ).joinToString("\n"),
        )

        val score = scoreTable(
            e,
            listOf(
                listOf("Номер", "Дата"),
                listOf(".286/2/18/138", "18.10.18"), // точка — вёрстка окна, не номер
                listOf("(286/2/18/137", "18.10.18"), // та же вёрстка, прочитанная скобкой
                listOf(".286/2/20/3.", "13.02.20"), // прилипло с обеих сторон
                listOf(".286/2/18/138⚠", "11.10.18"), // а это не вёрстка: 128 прочитан как 138
            ),
        )

        assertEquals(listOf("286/2/18/138", "286/2/18/137", "286/2/20/3"), score.found)
        assertEquals(3, score.matchedCells)
        // Свёртка края идентификатор не ослабляет: другой номер — другая строка. 128,
        // прочитанный дублем 138, потерян и лишний, а его дата — молчаливое расхождение.
        assertEquals(listOf("286/2/18/128"), score.lost)
        assertEquals(1, score.extra)
        assertEquals(listOf(CellDiff("286/2/18/128", 1, "11.10.18", null)), score.silent)
    }

    @Test
    fun `лишними считаются строки сверх неназванных, а не сверх названных`() {
        // В документе 4 строки, эталон назвал 2 (у остальных нет артикула) — значит две
        // неопознанные строки законны, третья уже придумана.
        val expectation = ledger(4, named = listOf(row("11004"), row("11006")))
        val body = listOf(listOf("11004", "Гречка", "120"), listOf("11006", "Рис", "40"))
        val unnamed = listOf(listOf("", "Соль", "10"), listOf("", "Сахар", "5"))

        assertEquals(0, scoreTable(expectation, listOf(head) + body + unnamed).extra)
        assertEquals(1, scoreTable(expectation, listOf(head) + body + unnamed + listOf(listOf("", "?", "?"))).extra)
    }

    @Test
    fun `подмена строк ловится, хотя длина файла сошлась`() {
        val expectation = ledger(3, named = listOf(row("11004"), row("11006"), row("11012")))
        val score = scoreTable(
            expectation,
            listOf(head, listOf("11004", "Гречка", "120"), listOf("12345", "?", "?"), listOf("54321", "?", "?")),
        )

        assertEquals(3, score.tableRows)
        assertEquals(listOf("11006", "11012"), score.lost)
        assertEquals(2, score.extra)
    }

    @Test
    fun `стена предупреждений — не честность, а невыполненное действие`() {
        // Живой прогон кадра 23 пометил 387 ячеек из ~430 при нулевом молчаливом расхождении.
        // Формально честно, по существу — задание перепроверить всё вручную.
        val score = scoreTable(
            ledger(2, named = listOf(row("11004", 2 to "120"), row("11006", 2 to "40"))),
            listOf(head, listOf("11004⚠", "Гречка⚠", "120⚠"), listOf("11006⚠", "Рис⚠", "40⚠")),
        )

        assertEquals(2, score.matchedCells)
        assertTrue(score.silent.isEmpty())
        assertEquals(1.0, score.markedShare!!, 0.001)
        assertEquals(listOf(TableFailure.WARNING_WALL), score.failures)
    }

    // -- лист документа — не таблица (#266, ревью) --

    /**
     * Замер по живому файлу кадра 18 (03.08.2026): 13 пометок на 36 ячеек таблицы — стена стоит;
     * те же 13 пометок на 55 ячеек **листа** (реквизиты сверху, примечание снизу, хвост
     * «непрочитанного» под подписью) — стены нет. Для человека не изменилось ничего, а провал из
     * отчёта исчез: чем больше документа вокруг сетки, тем зеленее число. «Непрочитанное» при этом
     * поощрялось прямо — оно и есть самый длинный хвост.
     *
     * Поэтому метрика судит строки ТАБЛИЦЫ, а лист сужает до них по фактам самого файла.
     */
    @Test
    fun `документ вокруг сетки не разбавляет долю пометок и не выдумывает лишних строк`() {
        val expectation = ledger(2, named = listOf(row("11004", 2 to "120"), row("11006", 2 to "40")))
        val grid = listOf(head, listOf("11004⚠", "Гречка⚠", "120⚠"), listOf("11006⚠", "Рис⚠", "40⚠"))

        val bare = scoreTable(expectation, grid)
        val document = scoreTable(
            expectation,
            listOf(listOf("Накладная №7")) +
                listOf(listOf("Клиент", "Терминал Пр. 117")) +
                grid +
                listOf(listOf("Кладовщик не имеет права отпускать товар")) +
                listOf(listOf(UNREAD_CAPTION)) +
                listOf(listOf("‚ Be:"), listOf("MPs"), listOf("си")),
        )

        assertEquals("строк таблицы столько же", bare.tableRows, document.tableRows)
        assertEquals("выдуманных строк не прибавилось", bare.extra, document.extra)
        assertEquals("доля пометок не разбавлена", bare.markedShare!!, document.markedShare!!, 0.001)
        assertEquals("и стена по-прежнему стоит", bare.failures, document.failures)
        assertEquals(listOf(TableFailure.WARNING_WALL), document.failures)
    }

    /** Часть «непрочитанное» файл называет вслух — и всё, что ниже, таблицей не считается. */
    @Test
    fun `хвост непрочитанного в счёт таблицы не идёт`() {
        val score = scoreTable(
            ledger(2, named = listOf(row("11004", 2 to "120"), row("11006", 2 to "40"))),
            listOf(head, listOf("11004", "Гречка", "120"), listOf("11006", "Рис", "40")) +
                listOf(listOf(UNREAD_CAPTION), listOf("Кладовщик", "не", "имеет")),
        )

        assertEquals(2, score.tableRows)
        assertEquals(0, score.extra)
        assertTrue("таблица сдана — хвост её не топит", score.passed)
    }

    @Test
    fun `артикул под меткой ⚠ всё равно узнаёт свою строку`() {
        // На живой ведомости помечено почти всё, ключи в том числе. Если бы метка мешала опознанию,
        // метрика объявила бы потерянными все строки сразу и мерила бы собственный разбор маркеров.
        val score = scoreTable(
            ledger(2, named = listOf(row("11004", 2 to "120"), row("11006", 2 to "40"))),
            listOf(head, listOf("11004⚠", "Гречка", "120"), listOf("11006⚠", "Рис", "40")),
        )

        assertEquals(listOf("11004", "11006"), score.found)
        assertEquals(2, score.matchedCells)
    }

    @Test
    fun `формат за расхождение не считается, а другое число — считается`() {
        val score = scoreTable(
            ledger(2, named = listOf(row("11004", 2 to "1 200"), row("11006", 2 to "1,5"))),
            listOf(head, listOf("11004", "Гречка", "1200"), listOf("11006", "Рис", "15")),
        )

        assertEquals("разрядный пробел значения не меняет", 1, score.matchedCells)
        assertEquals("«1,5» и «15» — разные числа", 1, score.silent.size)
    }

    @Test
    fun `шапку эталона не отдаём первой строке вслепую`() {
        // Модель, потерявшая шапку, отдаёт данными первую же строку — слепое отбрасывание
        // съело бы настоящую строку документа.
        val score = scoreTable(
            ledger(2, named = listOf(row("11004", 2 to "120"), row("11006", 2 to "40"))),
            listOf(listOf("11004", "Гречка", "120"), listOf("11006", "Рис", "40")),
        )

        assertEquals(2, score.tableRows)
        assertEquals(listOf("11004", "11006"), score.found)
        assertTrue(score.passed)
    }

    @Test
    fun `таблицы нет вовсе — всё потеряно и всё молчит`() {
        val score = scoreTable(ledger(2, named = listOf(row("11004", 2 to "120"))), emptyList())

        assertEquals(0, score.tableRows)
        assertEquals(listOf("11004"), score.lost)
        assertEquals(1, score.silent.size)
        assertNull("помечать нечего — доли нет", score.markedShare)
        assertEquals(0.0, score.cellShare!!, 0.001)
    }

    @Test
    fun `эталон без сверенных значений не даёт «сдано» — судить было нечем`() {
        // Ровно тот случай, что лежит в репозитории: заготовка кадра 23 называет 27 артикулов и
        // ни одного значения. Файл, где артикулы прочитаны и ширина сошлась, а содержимое пусто,
        // не даёт ни потерь, ни молчаливых расхождений — и «сдано» здесь было бы статусом,
        // выданным за отсутствие проверки.
        val score = scoreTable(
            ledger(2, named = listOf(row("11004"), row("11006"))),
            listOf(head, listOf("11004", "", ""), listOf("11006", "", "")),
        )

        assertEquals(0, score.checkedCells)
        assertTrue("причин провала действительно нет", score.failures.isEmpty())
        assertFalse("сверять было нечего — значит не сдано", score.passed)
        assertTrue(score.unjudged)
    }

    @Test
    fun `отчёт не говорит «сдано» там, где сверять было нечего`() {
        // Числа читает человек в отчёте, а не в полях: пин стоит на самом тексте.
        val text = renderTableScore(
            scoreTable(
                ledger(2, named = listOf(row("11004"), row("11006"))),
                listOf(head, listOf("11004", "", ""), listOf("11006", "", "")),
            ),
        )

        assertTrue(text, text.contains("проверить нечем"))
        assertFalse(text, text.contains("**всё верно**"))
    }

    @Test
    fun `одно сверенное значение уже даёт право на «сдано»`() {
        val text = renderTableScore(
            scoreTable(
                ledger(2, named = listOf(row("11004", 2 to "120"), row("11006"))),
                listOf(head, listOf("11004", "Гречка", "120"), listOf("11006", "Рис", "40")),
            ),
        )

        assertTrue(text, text.contains("**всё верно**"))
    }

    // --- формат эталона ---

    @Test
    fun `эталон читается — числа документа, ключ и сверенные ячейки`() {
        val text = listOf(
            "# ведомость владельца",
            "строк 35   # без строки шапки",
            "колонок 8",
            "ключ 1",
            "шапка да",
            "--",
            "11004\tГречка\t\t120",
            "11006",
        ).joinToString("\n")

        val expectation = parseTableExpectation("23", text)

        assertEquals(35, expectation.documentRows)
        assertEquals(8, expectation.documentColumns)
        assertEquals(0, expectation.keyColumn)
        assertTrue(expectation.header)
        assertEquals(listOf("11004", "11006"), expectation.namedRows.map { it.key })
        assertEquals(mapOf(1 to "Гречка", 3 to "120"), expectation.namedRows.first().cells)
        assertEquals("названо не значит сверено", emptyMap<Int, String>(), expectation.namedRows.last().cells)
    }

    @Test
    fun `без числа строк эталон не читается — иначе потерянные строки исчезнут`() {
        // Соблазн — взять число строк из числа названных. Тогда эталон, назвавший 27 строк из 35,
        // объявил бы документ 27-строчным, и восемь потерь растворились бы вместе с провалом.
        val e = assertThrows(IllegalArgumentException::class.java) {
            parseTableExpectation("23", "колонок 8\n--\n11004")
        }
        assertTrue(e.message!!.contains("строк"))
    }

    @Test
    fun `строка эталона без ключа и повторный ключ — ошибка, а не тихий пропуск`() {
        assertThrows(IllegalArgumentException::class.java) {
            parseTableExpectation("23", "строк 3\nколонок 3\n--\n\t\tГречка")
        }
        assertThrows(IllegalArgumentException::class.java) {
            parseTableExpectation("23", "строк 3\nколонок 3\n--\n11004\n11004")
        }
        assertThrows(IllegalArgumentException::class.java) {
            parseTableExpectation("23", "строк 1\nколонок 3\nвесна да\n--\n11004")
        }
    }

    @Test
    fun `названных строк не может быть больше, чем строк в документе`() {
        assertThrows(IllegalArgumentException::class.java) {
            parseTableExpectation("23", "строк 1\nколонок 3\n--\n11004\n11006")
        }
    }

    /**
     * Замер кадра 23: в бланке напечатано `Пластівці вівсяні “Екстра”` типографскими
     * лапками, модель отвечает прямыми — и метрика винила чтение за оформление.
     */
    @Test
    fun `стиль кавычки не расхождение, а слово внутри неё — расхождение`() {
        val e = parseTableExpectation(
            "к",
            "строк 2\nколонок 2\nключ 1\n--\n11401\tПластівці вівсяні “Екстра”\n",
        )

        val same = scoreTable(e, listOf(listOf("11401", "Пластівці вівсяні \"Екстра\"")))
        assertEquals(1, same.matchedCells)
        assertEquals(0, same.silent.size)

        val other = scoreTable(e, listOf(listOf("11401", "Пластівці вівсяні \"Преміум\"")))
        assertEquals(0, other.matchedCells)
        assertEquals(1, other.silent.size)
    }

    @Test
    fun `заготовка эталона кадра 23 читается — её правит человек руками`() {
        // Путь от каталога модуля: рабочий каталог теста у Gradle — core/flow. Эталон дописывает
        // человек, и опечатка в нём иначе всплыла бы только после прогона на устройстве — то есть
        // через две с половиной минуты работы и один потраченный кадр.
        val file = File("../../tools/corpus/23.expected.tsv")
        assertTrue("не найден ${file.absolutePath}", file.exists())

        val expectation = parseTableExpectation("23", file.readText())

        assertEquals(35, expectation.documentRows)
        // Девять, а не восемь: Арт.№ + Найменування + семь подразделений в шапке кадра.
        // Заготовка называла восемь, и «ширина не совпала» держалась в отчёте как провал —
        // пересчитано по самому кадру 02.08.2026.
        assertEquals(9, expectation.documentColumns)
        assertEquals(0, expectation.keyColumn)
        assertEquals("артикулы кадра 23", 27, expectation.namedRows.size)
        assertTrue("11004" in expectation.namedRows.map { it.key })
        // Значения сверены глазами, иначе метрика печатала бы «сдано» за отсутствие проверки
        // (#340): восемнадцать чистых значений первой числовой колонки.
        // 26, а не 18: после решения владельца (#345 — брать оба) ячейки, где рука спорит с
        // печатью, стали проверяемыми, и восемь из них тоже сверены.
        assertEquals("строк со сверенными значениями", 26, expectation.namedRows.count { it.cells.isNotEmpty() })
        // Знаменатель метрики считается в ЯЧЕЙКАХ, а не в строках: 26 чисел плюс 23 названия. Без
        // этого числа потерянная при правке табуляция убирает одно значение из проверки, строка
        // остаётся «со значениями», и замер молча становится меньше обещанного.
        assertEquals("сверенных значений", 49, expectation.namedRows.sumOf { it.cells.size })
    }

    /**
     * Читаются ВСЕ эталоны каталога, а не только тот, что вспомнили. Эталон пишет человек глазами
     * по фотографии; опечатка в нём — табуляция вместо пробела, «колонок» без числа, дважды
     * названный ключ — иначе всплывает только на устройстве: после минут ожидания «В Excel» и
     * потраченного кадра. Тест ходит по каталогу, а не по списку имён, чтобы новый эталон
     * попадал под проверку самим фактом появления.
     */
    @Test
    fun `все эталоны каталога разбираются и называют хотя бы одно сверенное значение`() {
        val dir = File("../../tools/corpus")
        val files = dir.listFiles { f: File -> f.name.endsWith(EXPECTED_SUFFIX) }.orEmpty().sortedBy { it.name }
        assertTrue("нет эталонов в ${dir.absolutePath}", files.isNotEmpty())

        files.forEach { file ->
            val frame = file.name.removeSuffix(EXPECTED_SUFFIX)
            val expectation = parseTableExpectation(frame, file.readText())

            assertTrue("$frame — «строк» должно быть больше нуля", expectation.documentRows > 0)
            assertTrue("$frame — «колонок» должно быть больше нуля", expectation.documentColumns > 0)
            assertTrue("$frame — не названо ни одной строки", expectation.namedRows.isNotEmpty())
            // «Нечем судить» (TableScore.unjudged) — законный исход метрики, но не законное
            // состояние эталона в репозитории: файл без единого сверенного значения молча
            // пропустит любую таблицу с правильными ключами.
            assertTrue(
                "$frame — ни одного сверенного значения: метрике нечем судить",
                expectation.namedRows.any { it.cells.isNotEmpty() },
            )

            // Эталон не должен противоречить сам себе: таблица, прочитанная идеально, обязана его
            // пройти. Ловушка тут не выдуманная — колонка-ключ ищется как та, где нашлось больше
            // всего эталонных ключей, и слабый ключ («1»…«5» на технологической карте) мог бы
            // найтись в другой колонке. Тогда метрика мерила бы съехавшую сетку и врала бы на
            // любом файле, а причину искали бы в чтении.
            val score = scoreTable(expectation, perfectTable(expectation))
            assertTrue("$frame — эталон не проходит сам себя: ${score.failures}", score.passed)
            assertEquals("$frame — сверенных ячеек", score.checkedCells, score.matchedCells)
        }
    }

    /**
     * Таблица, прочитанная идеально: строка шапки (если эталон её заявил), названные строки с теми
     * значениями, что человек сверил, и столько же безымянных строк, сколько документ мог дать
     * сверх названных. Несверенные ячейки пусты — метрика их не судит.
     */
    private fun perfectTable(e: TableExpectation): List<List<String>> = buildList {
        if (e.header) add(List(e.documentColumns) { "шапка ${it + 1}" })
        e.namedRows.forEach { named ->
            add(
                List(e.documentColumns) { column ->
                    when (column) {
                        e.keyColumn -> named.key
                        else -> named.cells[column].orEmpty()
                    }
                },
            )
        }
        repeat(e.documentRows - e.namedRows.size) { add(List(e.documentColumns) { "" }) }
    }

    /**
     * Числа эталонов, снятые с кадров глазами 02.08.2026. Тест выше говорит «файл разбирается»,
     * этот — «в файле ровно то, что человек увидел»: строка, потерянная при правке, иначе тихо
     * уменьшит знаменатель метрики, и провал исчезнет вместе с ней.
     *
     * Считаются и строки, и **значения**. Знаменатель метрики ([TableScore.checkedCells]) —
     * ячейки, а не строки: пропавшая при правке табуляция убирает одно сверенное значение, строка
     * при этом остаётся «со значениями», и замер молча становится меньше того, что обещано в
     * `docs/CORPUS.md`. Проверено щупом на кадре 06: без счёта ячеек эталон, потерявший «діє з»
     * первой строки (24 значения → 23), проходил тест зелёным.
     *
     * Кадры 10 и 19 эталонов не имеют намеренно — причины названы в docs/CORPUS.md
     * («Приватность эталона»): на 10 колонку с названиями строк срезал край кадра, на 19 таблица
     * целиком состоит из персональных данных.
     */
    @Test
    fun `эталоны кадров 01, 04, 06 и 18 держат числа, снятые с кадров`() {
        // кадр → строк, колонок, колонка-ключ (0-based), шапка, названо строк, из них со
        // значениями, сверенных значений (ячеек) — последнее число и есть то, что напечатано
        // в docs/CORPUS.md как «сверено значений».
        val expected = mapOf(
            "01" to Shape(12, 7, 0, header = true, named = 5, checked = 5, cells = 25),
            "04" to Shape(12, 7, 0, header = true, named = 5, checked = 5, cells = 25),
            "06" to Shape(16, 7, 1, header = true, named = 16, checked = 8, cells = 24),
            "18" to Shape(2, 7, 1, header = false, named = 2, checked = 2, cells = 10),
        )

        expected.forEach { (frame, shape) ->
            val file = File("../../tools/corpus/$frame$EXPECTED_SUFFIX")
            assertTrue("не найден ${file.absolutePath}", file.exists())
            val e = parseTableExpectation(frame, file.readText())

            assertEquals("$frame — строк", shape.rows, e.documentRows)
            assertEquals("$frame — колонок", shape.columns, e.documentColumns)
            assertEquals("$frame — колонка-ключ", shape.keyColumn, e.keyColumn)
            assertEquals("$frame — шапка", shape.header, e.header)
            assertEquals("$frame — названо строк", shape.named, e.namedRows.size)
            assertEquals(
                "$frame — строк со сверенными значениями",
                shape.checked,
                e.namedRows.count { it.cells.isNotEmpty() },
            )
            assertEquals("$frame — сверенных значений", shape.cells, e.namedRows.sumOf { it.cells.size })
        }
    }

    private data class Shape(
        val rows: Int,
        val columns: Int,
        val keyColumn: Int,
        val header: Boolean,
        val named: Int,
        val checked: Int,
        val cells: Int,
    )
}

private const val EXPECTED_SUFFIX = ".expected.tsv"
