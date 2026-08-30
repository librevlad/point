package com.point.desktop.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.point.core.flow.AI_PROVIDERS
import com.point.desktop.COMPACT_HEIGHT
import com.point.desktop.COMPACT_WIDTH
import com.point.desktop.PcConfig
import com.point.desktop.desktopState
import com.point.desktop.sceneNodes
import com.point.desktop.settle
import com.point.desktop.showCompact
import com.point.desktop.signedInAccount
import com.point.desktop.texts
import com.point.desktop.tooWide
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Строка сервиса в настройках не зависит от длины примечания (#876).
 *
 * Живой прогон владельца: описание сервиса вытянулось в колонку по одной букве. Причина —
 * в `Row` рядом со взвешенной колонкой стоял текст без всякого ограничения ширины: он брал
 * себе столько, сколько просил, а колонке доставался остаток. На узком окне остатка не
 * оставалось вовсе.
 *
 * Сторожило это чтение исходника: подстроки `weight(1f)` и `widthIn(max = 560.dp)` в тексте
 * файла настроек. Оправдание — «проверить раскладку Compose Desktop без окна нечем» — с
 * появлением `CompactScene` перестало быть правдой (#1314). Здесь открывается тот же раздел
 * ключей в окне размером с компакт, и меряется сама раскладка: во что встали строки и не
 * вылезло ли что за край окна.
 *
 * Второй подстроки — `widthIn(max = 560.dp)` — в продукте больше нет, и охранять там было
 * нечего (#1314). Предел не действовал ни при какой ширине окна: он стоял после
 * `fillMaxWidth()`, а тот прижимает ширину к родительской и снизу, и сверху. Замерено на этой
 * же сцене: в окне шириной 1000 строки раздела вставали в 976, а не в 560. Окно компакта к
 * тому же уже этого предела и `resizable = false`, так что предел не сработал бы и в
 * правильном порядке. Зелёная подстрока при этом жила с самого #876 и говорила о написании
 * файла, а не о том, что видит человек, — ровно та болезнь, ради которой заведена #1314.
 */
@OptIn(ExperimentalTestApi::class)
class SettingsRowKeepsWidthTest {

    @Test
    fun `примечания у сервисов действительно длинные — короткими они не станут`() {
        val longest = AI_PROVIDERS.mapNotNull { it.freeNote }.maxByOrNull { it.length }.orEmpty()

        assertTrue("примечание пропало — проверять стало нечего", longest.isNotBlank())
        assertTrue("самое длинное примечание — «$longest»", longest.length > 30)
    }

    /**
     * Меряется не то, как написан файл, а то, во что встали строки: рассыпалась не сама
     * приписка, а текст рядом с ней, и сторож смотрит на весь экран сразу. Правило общее —
     * ни одна длинная строка раздела не стоит колонкой в букву, — и держит оно любую будущую
     * строку настроек, а не одну знакомую.
     */
    @Test
    fun `длинное примечание не ломает строку сервиса в колонку по букве`() =
        runDesktopComposeUiTest(COMPACT_WIDTH, COMPACT_HEIGHT) {
            val crumbled = openedService()
                .filter { node -> node.texts().any { it.length > SHORT } }
                .filter { it.size.width < LETTERS }
                .map { "«${it.texts().first()}» в ${it.size.width}" }

            assertTrue("строка встала колонкой по букве: $crumbled", crumbled.isEmpty())
        }

    /**
     * Нехватку места и создавала жёсткая ширина: раздел настроек человек читает в окне
     * компакта, и всё, что вырвалось за его край, он не видит вовсе.
     *
     * Меряется то же, что у соседних экранов окна (#1248): узлы шире окна. Видно так не всё —
     * узел, ужатый родителем своей шириной, шире окна не станет, — и обещать больше не надо.
     * Читаемого предела колонки этот сторож не обещает: предела в продукте нет (#1314).
     */
    @Test
    fun `колонка настроек не шире окна`() = runDesktopComposeUiTest(COMPACT_WIDTH, COMPACT_HEIGHT) {
        val wide = openedService().tooWide(COMPACT_WIDTH)

        assertTrue("шире окна компакта: $wide", wide.isEmpty())
    }

    /**
     * Раздел ключей с открытым сервисом — тем, у которого примечание самое длинное: на нём
     * нехватка места и вылезала. Вместе с ним на экран приходит и само примечание.
     *
     * Сервис открывается своим же нажатием, а не мышью по месту: в окне компакта его строка
     * стоит ниже сгиба, а разговор здесь про ширину, не про то, куда попал курсор.
     *
     * Примечание на экране проверяется здесь же: нет его — давить на ширину нечем, и оба
     * сторожа зеленели бы над пустым экраном, ничего о нём не сказав.
     */
    private fun SkikoComposeUiTest.openedService(): List<SemanticsNode> {
        val service = AI_PROVIDERS.filter { it.freeNote != null }.maxBy { it.freeNote.orEmpty().length }
        showCompact {
            CompactSettings(
                state = desktopState(),
                page = SettingsPage.KEYS,
                onPage = {},
                config = PcConfig(name = "Компьютер"),
                account = signedInAccount(),
                circle = emptyList(),
                busy = false,
                error = null,
                onWipe = {},
                onSave = {},
                onRightClick = { null },
                rightClickHolds = { null },
                onSweepNow = { 0 },
                onBack = {},
            )
        }

        onNodeWithText(service.name).performSemanticsAction(SemanticsActions.OnClick)
        settle()

        val nodes = sceneNodes()
        val note = service.freeNote.orEmpty()
        if (nodes.none { node -> node.texts().any { it == note } }) {
            throw AssertionError("примечания «$note» на экране нет вовсе — давить на ширину нечем")
        }
        return nodes
    }

    private companion object {

        /** Короткая строка — метка, номер или знак: ширина в букву для неё и есть её ширина. */
        const val SHORT = 12

        /** Уже этого строка перестаёт быть строкой: в такую ширину не встанет и пара слогов. */
        const val LETTERS = 60
    }
}
