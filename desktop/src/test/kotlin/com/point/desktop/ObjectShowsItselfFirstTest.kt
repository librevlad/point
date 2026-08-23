package com.point.desktop

import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.point.core.flow.PcRemoteAction
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import com.point.desktop.ui.CompactObject
import java.io.File
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
 */
@OptIn(ExperimentalTestApi::class)
class ObjectShowsItselfFirstTest {

    private val scene = File("src/main/kotlin/com/point/desktop/ui/ObjectScene.kt").readText()

    @Test
    fun `наверху стоит портал, а не текст`() {
        val portal = scene.substringAfter("internal fun PortalPreview(").substringBefore("\n}")

        assertTrue("текст снова наверху", !portal.contains("ObjectKind.TEXT"))
        assertTrue("снимок должен остаться наверху — он сам себе опознание",
            portal.contains("ObjectKind.IMAGE"))
    }

    @Test
    fun `текст стоит после знания и до действий`() = runDesktopComposeUiTest(COMPACT_WIDTH, COMPACT_HEIGHT) {
        val nodes = shownObject()

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
     * Меряется размер разметки, а не видимые границы: превью без предела строк обрезал бы
     * родитель ровно по окну, и «во весь экран» было бы не отличить от «в шесть строк».
     */
    @Test
    fun `свёрнутое превью не занимает весь экран окна`() = runDesktopComposeUiTest(COMPACT_WIDTH, COMPACT_HEIGHT) {
        val node = shownObject().firstOrNull { it.texts().any { text -> text.startsWith(HEAD) } }
            ?: throw AssertionError("превью не нашлось — сторож смотрит в пустоту, а не на текст")

        assertTrue(
            "свёрнутый текст занимает ${node.size.height} из $COMPACT_HEIGHT — это снова весь экран",
            node.size.height <= COMPACT_HEIGHT / 2,
        )
    }

    /** Сцена длинного текстового объекта — того, из-за которого и завелась беда #898. */
    private fun SkikoComposeUiTest.shownObject(): List<SemanticsNode> {
        val item = longText()
        val st = DesktopState(
            registry = DesktopRegistry(emptySet()),
            resolver = DesktopResolver(emptySet()),
            clipboard = { },
            journalStore = object : JournalStore {
                override fun load() = emptyList<JournalEntry>()
                override fun save(entries: List<JournalEntry>) = Unit
            },
        ).apply { setPhoneCaps(listOf(PcRemoteAction("call", ACTION, kinds = setOf("TEXT"), priority = 10))) }
        st.onReceived(item)

        showCompact { CompactObject(state = st, item = item, onBack = {}) }
        return sceneNodes()
    }

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

    /** Где стоит верх узла, на котором написано это. Порядок считается по разметке, не по виду. */
    private fun List<SemanticsNode>.top(what: String): Float? =
        firstOrNull { node -> node.texts().any { what in it } }?.positionInRoot?.y

    private fun List<SemanticsNode>.preview(): Float? =
        firstOrNull { node -> node.texts().any { it.startsWith(HEAD) } }?.positionInRoot?.y

    private companion object {

        /** Начало текста в файле — по нему узнаётся узел превью. */
        const val HEAD = "Начало длинного письма."

        const val AMOUNT = "500"

        const val ACTION = "Позвонить"
    }
}
