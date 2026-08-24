package com.point.desktop

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Длинный текст на компьютере дочитывается до конца (#1086).
 *
 * Живой прогон: файл в 267 000 байт открывался пятью строками с многоточием — и всё. Ни
 * раскрытия, ни счёта скрытого: человек не мог прочитать собственный текст дальше первых
 * трёхсот символов, хотя телефон тот же объект раскрывал.
 */
class LongTextOpensOnPcTest {

    @get:Rule val temp = TemporaryFolder()

    private val scene = File("src/main/kotlin/com/point/desktop/ui/ObjectScene.kt").readText()

    private val body = scene.substringAfter("internal fun Preview(").substringBefore("internal fun Knowledge(")

    @Test
    fun `читается только начало, и предел чтения назван честно`() {
        val file = temp.newFile("огромный.txt").apply { writeText("я".repeat(5_000)) }

        val read = readTextHead(file, limit = 1_000)

        assertEquals(1_000, read.text.length)
        assertTrue("чтение упёрлось в предел, а объект об этом молчит", read.atLimit)
    }

    @Test
    fun `короткий текст прочитан целиком и пределом не считается`() {
        val file = temp.newFile("записка.txt").apply { writeText("привет") }

        val read = readTextHead(file, limit = 1_000)

        assertEquals(6, read.text.length)
        assertFalse("короткий текст выдан за упёршийся в предел", read.atLimit)
    }

    @Test
    fun `пропавший файл не роняет показ`() {
        val read = readTextHead(File(temp.root, "нет-такого.txt"), limit = 1_000)

        assertTrue("исчезнувший файл притворился текстом", read.text.isEmpty())
        assertFalse(read.atLimit)
    }

    @Test
    fun `у обрезанного текста есть выход — раскрытие и обратный ход`() {
        assertTrue("текст обрывается насовсем — раскрыть нечем", body.contains("expandTextLabel"))
        assertTrue("раскрытый текст не свернуть обратно", body.contains("COLLAPSE_LABEL"))
        assertTrue(
            "раскрытие не замечает обрыва по строкам экрана",
            body.contains("hasVisualOverflow"),
        )
    }

    @Test
    fun `обещание раскрытия не переписано на ПК заново`() {
        val shared = File(repo, "core/ui/src/shared/kotlin/com/point/core/ui/TextPreviewText.kt")

        assertTrue("общий исходник обещаний пропал: $shared", shared.isFile)
        assertTrue("обещание раскрытия завели на ПК своё", "\"Показать" !in body)
    }
}
