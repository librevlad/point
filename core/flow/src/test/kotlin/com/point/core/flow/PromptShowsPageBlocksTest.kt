package com.point.core.flow

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Подпись колонки остаётся при своей колонке (#768).
 *
 * Живая охота 11.08.2026: на почтовой наклейке «КОМУ:» стоит чуть выше «ВІД:» — кадр снят
 * под наклоном. Сплошным списком строк это читается как «сначала КОМУ, потом Тарасенко»,
 * и модель дважды подряд объявила отправителя получателем, а получателя отправителем.
 */
class PromptShowsPageBlocksTest {

    private val label = listOf(
        "ВІД: 29.07/12:59\nПриватна особа\nТарасенко Світлана Сергіївна\n067 636 05 60",
        "КОМУ:\nПриватна особа\nДумброван Олександр Миколайович\nс.Бритівка (Одеська обл.)",
    )

    @Test
    fun `колонки названы блоками, и каждая подпись при своей`() {
        val prompt = understandPrompt(layoutOfBlocks(label))

        val sender = prompt.indexOf("ВІД:")
        val tarasenko = prompt.indexOf("Тарасенко")
        val receiver = prompt.indexOf("КОМУ:")
        val dumbrovan = prompt.indexOf("Думброван")

        assertTrue("подпись отправителя перед своим именем", sender in 0..<tarasenko)
        assertTrue("имя отправителя перед подписью получателя", tarasenko in 0..<receiver)
        assertTrue("подпись получателя перед своим именем", receiver in 0..<dumbrovan)
    }

    @Test
    fun `границы блоков видны модели, а не подразумеваются`() {
        val prompt = understandPrompt(layoutOfBlocks(label))

        assertTrue(prompt, "Блок 1" in prompt && "Блок 2" in prompt)
    }

    @Test
    fun `одноблочная страница выглядит как прежде — списком элементов`() {
        val prompt = understandPrompt(layoutOf("Договір\nвід 12 березня\nСторона А"))

        assertFalse("блоков нет — и слова о них нет", "Блок 1" in prompt)
        assertTrue(prompt, "P1: Договір" in prompt)
    }
}
