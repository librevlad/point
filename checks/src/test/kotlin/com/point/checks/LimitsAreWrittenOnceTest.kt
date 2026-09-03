package com.point.checks

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Пределы и расчёты — по одному месту, а не по копии на устройство (#861).
 *
 * Каждое из этих чисел — обещание человеку: объект тяжелее просто не поедет. Записанные
 * дважды, они разъезжаются молча, и человек получает «на телефоне работает, на компьютере
 * нет» без единого слова о том, почему.
 *
 * Живёт в `:checks` (#1293): проверка читает файлы `:data`, `:desktop` и `:executors`. Сами
 * общие числа и расчёты остались тестами `:core:flow` — там, где они объявлены.
 */
class LimitsAreWrittenOnceTest {

    private fun source(path: String) = File(repo, path).readText()

    @Test
    fun `предел ссылки не набран числом второй раз`() {
        val guilty = listOf(
            "data/src/main/kotlin/com/point/data/RelayDropLink.kt",
            "desktop/src/main/kotlin/com/point/desktop/DesktopDrop.kt",
        ).filterNot { source(it).contains("com.point.core.flow.MAX_DROP_BYTES") }

        assertTrue("предел объявлен заново: $guilty", guilty.isEmpty())
    }

    @Test
    fun `предел записи не набран числом второй раз`() {
        // Клиент один на оба устройства и живёт рядом с пределом (#1379): зовёт его по имени.
        val guilty = listOf(
            "core/flow/src/main/kotlin/com/point/core/flow/GroqWhisperSpeechToText.kt",
        ).filterNot { source(it).contains("MAX_SPEECH_BYTES") }

        assertTrue("предел объявлен заново: $guilty", guilty.isEmpty())
    }

    /**
     * Срок брошенного — один на телефон и компьютер (#1317, решение владельца 29.08.2026).
     *
     * Число суток жило только у телефона, и очередь ПК→телефон осталась вовсе без срока:
     * брошенные вещи и записи-исходы копились в папке годами. Записанный по копии на
     * устройство, срок разъедется так же молча, как пределы веса.
     *
     * Спрашивается и то, и другое: имя общего числа — на месте, а само число рядом с уборкой
     * не набрано. Одного упоминания мало — сторож с ним оставался зелёным ровно тогда, когда
     * сутки вписывали руками второй раз.
     *
     * Держат срок и вычитают его — разные места, и спрашивается с них разное. Имя общего числа
     * есть только там, где срок объявлен; запуск компьютера его не называет — он зовёт уборку,
     * — но набрать сутки числом прямо там можно так же легко, и тогда очередь начнёт забывать
     * по своему числу, а телефон по общему (#1317).
     */
    @Test
    fun `срок брошенного не набран числом второй раз`() {
        val places = listOf(
            "app/src/main/kotlin/com/point/PointApplication.kt",
            "desktop/src/main/kotlin/com/point/desktop/Abandoned.kt",
        )
        val guilty = places.filterNot { code(source(it)).contains("COPY_LIFETIME_MS") }
        assertTrue("срок объявлен заново: $guilty", guilty.isEmpty())

        val counting = places + "desktop/src/main/kotlin/com/point/desktop/Main.kt"
        val typed = counting.filter { dayTypedAgain(code(source(it))) }
        assertTrue("сутки набраны числом рядом с уборкой: $typed", typed.isEmpty())
    }

    @Test
    fun `цикл уменьшения не вписан руками второй раз`() {
        val guilty = listOf(
            "executors/src/main/kotlin/com/point/executors/Bitmaps.kt",
            "data/src/main/kotlin/com/point/data/TesseractTextRecognizer.kt",

            // Кадр выделения, замазывания и чтения на устройстве (#1013): его ужатие и есть
            // тот перевод координат, по которому метка поиска встаёт на найденную строку.
            "data/src/main/kotlin/com/point/data/ImageDecode.kt",
        ).filterNot { source(it).contains("sampleSizeFor(") }

        assertTrue("расчёт повторён: $guilty", guilty.isEmpty())
    }
}

/** Сутки миллисекундами — то самое число, второго набора которого сторож и не хочет. */
private const val DAY_MS = 86_400_000L

/**
 * Набраны ли здесь сутки руками — в любом написании (#1317).
 *
 * Сторож сверял буквы и ловил два написания из многих: `1000L * 60 * 60 * 24` и `86400L * 1000`
 * проходили мимо него молча. Написаний у одного числа столько, сколько способов его
 * перемножить, и списком они не кончаются — поэтому считается само произведение. Цепочка из
 * одного числа тоже произведение: `86_400_000` попадается ею же.
 */
private fun dayTypedAgain(code: String): Boolean = PRODUCT.findAll(code).any { chain ->
    runCatching {
        chain.value.split('*')
            .map { it.trim().trimEnd('L', 'l').replace("_", "").toLong() }
            .reduce(Math::multiplyExact)
    }.getOrNull() == DAY_MS
}

/** Цепочка целых чисел через `*` — самая длинная, какая нашлась с этого места. */
private val PRODUCT = Regex("""\d[\d_]*[Ll]?(?:\s*\*\s*\d[\d_]*[Ll]?)*""")
