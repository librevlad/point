package com.point.data

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Слой чтения уходит в сыром кадре, а не в координатах ужатой копии (#1013).
 *
 * Ни один движок не читает сам снимок: оба берут его ужатую копию — длинный скриншот
 * 1080×7200 ложится на неё вдвое меньше. Если слой уйдёт в координатах копии, всё, что
 * рисует строку поверх кадра — метка поиска, область находки, «Взять фрагмент», —
 * промахнётся ровно во столько же раз и промахнётся молча: текст найден верно, подсвечена
 * чужая строка. Так и было на телефоне владельца: искали строку 099, подсвечивалась 049.
 *
 * Сторож стоит на самом шве, а не на переводе рядом. Кадр, взятый без своего перевода,
 * теряет ужатие прямо в точке вызова, и правильность самого перевода этого уже не ловит:
 * переводить будет нечем.
 */
class ReaderLayerLivesInRawFrameTest {

    private val readers = listOf(
        "data/src/localOcr/kotlin/com/point/data/PaddleOcrRecognizer.kt",
        "data/src/main/kotlin/com/point/data/TesseractTextRecognizer.kt",
    )

    private fun source(path: String) = File(repo, path).readText()

    @Test
    fun `движок берёт кадр вместе с его переводом, а не одну картинку`() {
        val guilty = readers.filter { source(it).contains("decodeBoundedUpright(") }

        assertTrue("кадр взят без перевода: $guilty", guilty.isEmpty())
    }

    @Test
    fun `слово ложится в слой переведённым в сырой кадр, и перевод записан в самом слое`() {
        val guilty = readers.filterNot {
            val text = source(it)
            text.contains(".toRaw(") && text.contains("transform = ")
        }

        assertTrue("слой остался в координатах ужатой копии: $guilty", guilty.isEmpty())
    }
}
