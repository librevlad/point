package com.point.desktop

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.point.core.flow.PcRemoteAction
import com.point.core.flow.knowledgeRows
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import com.point.desktop.ui.CompactList
import com.point.desktop.ui.CompactObject
import com.point.desktop.ui.PeekCard
import java.io.File
import javax.imageio.ImageIO
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Компакт-окно (решение владельца 2026-08-09): сцена объекта, список и peek-плашка.
 *
 * Имена этих проверок обещали, что человек увидит объект, знание и действия, а проверялось
 * одно — `снимок.length() > 0` после записи PNG (#1250). PNG нулевой длины не бывает: условие
 * не могло стать ложным, и пустой прямоугольник фона прошёл бы наравне с настоящим окном.
 *
 * Теперь у сцены спрашивается обещанное — по дереву семантики, в окне размером с компакт:
 * что видно в первом кадре, не прокручивая, и не шире ли что-нибудь самого окна. Высота
 * намеренно не проверяется: содержимое живёт в `verticalScroll` (`ObjectPane`, `RecentPane`),
 * выход за высоту — штатный. Снимки `build/render/compact-*.png` остаются артефактом для глаз.
 */
@OptIn(ExperimentalTestApi::class)
class CompactRenderTest {

    private val phoneActions = listOf(
        PcRemoteAction("call", "Позвонить", kinds = setOf("TEXT"), priority = 10),
        PcRemoteAction("share", "Поделиться", priority = 80),
    )

    /** Тот же чистый компьютер, что и у остальных тестов окна (#1019), плюс объявление телефона. */
    private fun state(journal: List<JournalEntry> = emptyList()) =
        desktopState(journal).apply { setPhoneCaps(phoneActions) }

    private fun rich(): InboxItem {
        val file = File.createTempFile("компакт-", ".txt").apply {
            writeText("Оплатите счёт 4411 до 26.04.2026.\nТел: +380671234567")
            deleteOnExit()
        }
        return InboxItem(
            PointObject(
                id = "rich",
                mime = "text/plain",
                uri = ScratchRef(file.absolutePath),
                state = ObjectState(ObjectKind.TEXT),
                metadata = mapOf(
                    "name" to NAME,
                    "semantic.summary" to SUMMARY,
                    "entity.phone" to "+380671234567",
                    "entity.phone.more" to "+380509876543",
                    "entity.amount" to "500",
                    "entity.amount.alt" to "0.00",
                    "investigated.qr-content" to "not_found",
                ),
            ),
        )
    }

    @Test
    fun `сцена объекта в компакте — вид, имя, знание и первое действие видны сразу`() =
        runDesktopComposeUiTest(COMPACT_WIDTH, COMPACT_HEIGHT) {

            // Одна и та же копия объекта и на экране, и в журнале (#1250): запись собиралась
            // по второму вызову rich(), а File.createTempFile каждый раз даёт новый файл —
            // путь в журнале не совпадал ни с чем, и половина фикстуры была мертва.
            val item = rich()
            val st = state(
                listOf(
                    JournalEntry(
                        path = item.obj.uri.value,
                        name = NAME,
                        kind = ObjectKind.TEXT.name,
                        mime = "text/plain",
                        source = ObjectSource.PHONE_RELAY,
                        at = 1L,
                    ),
                ),
            )
            st.onReceived(item)

            val frame = shot("compact-object") { CompactObject(state = st, item = item, onBack = {}) }
            val seen = frame.visibleText()

            assertTrue("вид объекта не виден без прокрутки: $seen", frame.shows(SUMMARY))
            assertTrue("имя объекта не видно без прокрутки: $seen", frame.shows(NAME))

            // Знание — то, что продукт сам считает понятым об этом объекте, а не список,
            // переписанный в тест руками. Отдай продукт пустой список — и проверка ниже
            // осталась бы зелёной на экране без единой строки знания, поэтому сначала
            // спрашивается, что строки вообще есть: фикстура кладёт два факта, телефон и сумму.
            val rows = knowledgeRows(item.obj.metadata)
            assertTrue("продукт не отдал строк знания об этом объекте — проверять нечего: $rows", rows.size >= 2)

            val unseen = rows.filterNot { frame.shows(it.value) }
            assertTrue("строки знания уехали под сгиб: ${unseen.map { it.name }} · видно $seen", unseen.isEmpty())

            assertTrue("первое действие не видно без прокрутки: $seen", frame.shows(phoneActions.first().label))
            assertTrue("шире окна компакта: ${frame.tooWide(COMPACT_WIDTH)}", frame.tooWide(COMPACT_WIDTH).isEmpty())
        }

    @Test
    fun `список компакта — свежий объект и то, что было раньше, видны сразу`() =
        runDesktopComposeUiTest(COMPACT_WIDTH, COMPACT_HEIGHT) {
            val st = state(
                listOf(
                    JournalEntry(
                        path = "/tmp/старое.pdf",
                        name = EARLIER,
                        kind = ObjectKind.PDF.name,
                        mime = "application/pdf",
                        source = ObjectSource.PHONE_RELAY,
                        at = 1L,
                    ),
                ),
            )
            st.onReceived(rich())

            val frame = shot("compact-list") {
                CompactList(
                    state = st,
                    items = st.items.value,
                    onOpen = {},
                    onTakeClipboard = {},
                    onGrabScreen = {},
                    onSettings = {},
                    onHide = {},
                )
            }

            assertTrue("пришедшего объекта нет в списке: ${frame.visibleText()}", frame.shows(NAME))
            assertTrue("того, что было раньше, в списке нет: ${frame.visibleText()}", frame.shows(EARLIER))
            assertTrue("шире окна компакта: ${frame.tooWide(COMPACT_WIDTH)}", frame.tooWide(COMPACT_WIDTH).isEmpty())
        }

    @Test
    fun `peek-плашка называет объект и не вылезает за свой размер`() =
        runDesktopComposeUiTest(PEEK_WIDTH, PEEK_HEIGHT) {
            val frame = shot("compact-peek") { PeekCard(item = rich(), onOpen = {}, onDismiss = {}) }

            assertTrue("плашка не назвала объект: ${frame.visibleText()}", frame.shows(NAME))
            assertTrue("шире плашки: ${frame.tooWide(PEEK_WIDTH)}", frame.tooWide(PEEK_WIDTH).isEmpty())
        }

    /** Первый кадр окна: снимок ложится в `build/render` для глаз, узлы — для проверок. */
    private fun SkikoComposeUiTest.shot(name: String, content: @Composable () -> Unit): List<androidx.compose.ui.semantics.SemanticsNode> {
        showCompact(content)

        val out = File("build/render/$name.png").apply { parentFile.mkdirs() }
        ImageIO.write(captureToImage().toAwtImage(), "png", out)

        return sceneNodes()
    }

    private companion object {

        /** Имя, вид и прошлая запись — данные фикстуры: их и должно быть видно на экране. */
        const val NAME = "Счёт 4411"
        const val SUMMARY = "Оплата счёта до срока"
        const val EARLIER = "счёт-март.pdf"
    }
}
