package com.point.desktop

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.point.core.flow.PcRemoteAction
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import com.point.core.ui.kindMarkLabel
import com.point.core.ui.kindMarkOf
import com.point.desktop.ui.CompactObject
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Объект на компьютере сначала называет себя, а потом показывает содержимое (#898).
 *
 * Текстовый объект открывался стеной сырого текста в четырнадцать строк: вид, знание и
 * действия оставались за нижним краем окна, и человек видел не объект Point, а кусок файла. На
 * телефоне порядок другой: портал и вид → знание → текст → действия.
 *
 * Сторожил этот порядок разбор исходника на подстроки (#1248): кусок `ObjectScene.kt` между
 * двумя именами функций, а в нём — регэксп по числу строк. Проверка о написании файла, а не о
 * том, что человек видит: переименуй функцию — и вырезанный кусок станет пустым, а сторож
 * зелёным. Здесь та же сцена рисуется в окне размером с компакт, и спрашивается она сама.
 *
 * Чтения исходника в файле не осталось вовсе (#1314): последний кусок — «наверху стоит
 * портал» — тоже меряет сцену, каждый вид объекта своей.
 */
@OptIn(ExperimentalTestApi::class)
class ObjectShowsItselfFirstTest {

    /**
     * Знак вида стоит выше текста, а не текст выше всего (#898).
     *
     * Знак не несёт слов, и в дереве семантики он виден по своей подписи. Спрашивается она у
     * продукта тем же вызовом, каким он её и ставит: переписанное сюда слово разошлось бы с
     * экраном молча.
     */
    @Test
    fun `наверху стоит портал, а не текст`() = runDesktopComposeUiTest(COMPACT_WIDTH, COMPACT_HEIGHT) {
        val item = longText()
        val nodes = shownObject(item)

        val portal = nodes.mark(kindMarkLabel(kindMarkOf(item.obj)))
            ?: throw AssertionError("портала на экране нет вовсе — наверху не стоит ничего")
        val preview = nodes.preview() ?: throw AssertionError("текста на экране нет вовсе")

        assertTrue("текст снова наверху", portal < preview)
    }

    /**
     * У снимка наверху он сам: снимок — сам себе опознание, и знак вида поверх него лишний.
     *
     * Снимок слов не несёт и подписи у него нет, поэтому он ищется на самом кадре: фикстура
     * заливается цветом, которого в палитре Point нет, и на кадре его видно ровно там, где
     * человек видит снимок.
     */
    @Test
    fun `у снимка наверху он сам, а не знак вида`() = runDesktopComposeUiTest(COMPACT_WIDTH, COMPACT_HEIGHT) {
        val item = picture()
        val nodes = shownObject(item)

        assertNull(
            "поверх снимка встал знак вида — снимок сам себе опознание",
            nodes.mark(kindMarkLabel(kindMarkOf(item.obj))),
        )

        val name = nodes.top(NAME) ?: throw AssertionError("имени объекта на экране нет вовсе")
        val shown = captureToImage().toAwtImage().rowsOf(PAINT)

        assertTrue("снимка на экране нет вовсе — залито строк ${shown.size}", shown.size > PAINT_ROWS)
        assertTrue("снимок стоит ниже слов об объекте, а не наверху", shown.last() < name)
    }

    @Test
    fun `текст стоит после знания и до действий`() = runDesktopComposeUiTest(COMPACT_WIDTH, COMPACT_HEIGHT) {
        val nodes = shownObject(longText())

        val knowledge = nodes.top(AMOUNT) ?: throw AssertionError("знания на экране нет вовсе")
        val preview = nodes.preview() ?: throw AssertionError("текста на экране нет вовсе")
        val action = nodes.top(ACTION) ?: throw AssertionError("действий на экране нет вовсе")

        assertTrue("текст стоит выше знания", knowledge < preview)
        assertTrue("текст стоит ниже действий", preview < action)
    }

    /**
     * Свёрнутый текст приходит без спроса, поэтому у него есть предел. Раскрытый занимает
     * столько, сколько нужно, — его раскрыл человек (#1086), и мерить его нечем и незачем.
     *
     * Предел — высота окна (решение владельца по #1248 дословно: «против высоты окна»): беда
     * #898 в том, что текст занял весь экран, и о ней сторож и говорит. Сколько именно строк
     * показывать свёрнутым — не его вопрос.
     *
     * Меряется размер разметки, а не видимые границы: превью без предела строк обрезал бы
     * родитель ровно по окну, и «во весь экран» было бы не отличить от «в шесть строк».
     */
    @Test
    fun `свёрнутое превью не занимает весь экран окна`() = runDesktopComposeUiTest(COMPACT_WIDTH, COMPACT_HEIGHT) {
        val node = shownObject(longText()).firstOrNull { it.texts().any { text -> text.startsWith(HEAD) } }
            ?: throw AssertionError("превью не нашлось — сторож смотрит в пустоту, а не на текст")

        assertTrue(
            "свёрнутый текст занимает ${node.size.height} из $COMPACT_HEIGHT — это снова весь экран",
            node.size.height < COMPACT_HEIGHT,
        )
    }

    /** Сцена объекта — та же, что открывается человеку в окне компакта. */
    private fun SkikoComposeUiTest.shownObject(item: InboxItem): List<SemanticsNode> {
        val st = desktopState()
            .apply { setPhoneCaps(listOf(PcRemoteAction("call", ACTION, kinds = setOf("TEXT"), priority = 10))) }
        st.onReceived(item)

        showCompact { CompactObject(state = st, item = item, onBack = {}) }
        return sceneNodes()
    }

    /** Объект, из-за которого и завелась беда #898: длинный текст. */
    private fun longText(): InboxItem {
        val file = File.createTempFile("длинный-", ".txt").apply {
            writeText(HEAD + " " + (1..400).joinToString(" ") { "слово$it" })
            deleteOnExit()
        }
        return InboxItem(
            PointObject(
                id = "long",
                mime = "text/plain",
                uri = ScratchRef(file.absolutePath),
                state = ObjectState(ObjectKind.TEXT),
                metadata = mapOf("entity.amount" to AMOUNT),
            ),
        )
    }

    /** Снимок: настоящий файл рядом, залитый цветом, какого в палитре Point нет. */
    private fun picture(): InboxItem {
        val file = File.createTempFile("снимок-", ".png").apply {
            val paper = BufferedImage(300, 200, BufferedImage.TYPE_INT_RGB)
            paper.createGraphics().apply {
                color = java.awt.Color(PAINT)
                fillRect(0, 0, paper.width, paper.height)
                dispose()
            }
            ImageIO.write(paper, "png", this)
            deleteOnExit()
        }
        return InboxItem(
            PointObject(
                id = "снимок",
                mime = "image/png",
                uri = ScratchRef(file.absolutePath),
                state = ObjectState(ObjectKind.IMAGE),
                metadata = mapOf("name" to NAME),
            ),
        )
    }

    /** Где стоит верх узла, на котором написано это. Порядок считается по разметке, не по виду. */
    private fun List<SemanticsNode>.top(what: String): Float? =
        firstOrNull { node -> node.texts().any { what in it } }?.positionInRoot?.y

    /** Где стоит верх знака вида — узла, который зовётся этой подписью. */
    private fun List<SemanticsNode>.mark(label: String): Float? =
        firstOrNull { it.label() == label }?.positionInRoot?.y

    private fun List<SemanticsNode>.preview(): Float? =
        firstOrNull { node -> node.texts().any { it.startsWith(HEAD) } }?.positionInRoot?.y

    /** Строки кадра, на которых виден этот цвет, — то место, где человек видит снимок. */
    private fun BufferedImage.rowsOf(colour: Int): List<Int> =
        (0 until height).filter { y -> (0 until width).any { x -> getRGB(x, y) and 0xFFFFFF == colour } }

    private companion object {

        /** Начало текста в файле — по нему узнаётся узел превью. */
        const val HEAD = "Начало длинного письма."

        /** Имя снимка — по нему на сцене находятся слова об объекте. */
        const val NAME = "Снимок с дороги"

        /** Заливка снимка: в палитре Point такого цвета нет, спутать его на кадре не с чем. */
        const val PAINT = 0x07E76F

        /** Столько строк кадра залито снимком, когда он и правда нарисован, а не мелькнул. */
        const val PAINT_ROWS = 60

        const val AMOUNT = "500"

        const val ACTION = "Позвонить"
    }
}
