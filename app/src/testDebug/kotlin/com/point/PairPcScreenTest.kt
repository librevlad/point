package com.point

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.point.core.flow.DiscoveredPc
import com.point.core.flow.LinkPath
import com.point.core.flow.LinkState
import com.point.core.flow.PcPairing
import com.point.core.flow.PcSearch
import com.point.core.ui.theme.PointTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Что экран «Компьютер» говорит человеку про связь и про поиск (#451, #458).
 *
 * Обе находки были не в логике, а в том, что человек ЧИТАЕТ, — поэтому судит их экран, а не
 * состояние: «ещё не связывались» и пустое место вместо поиска ловятся только чтением экрана.
 */
@RunWith(RobolectricTestRunner::class)
class PairPcScreenTest {

    @get:Rule val compose = createComposeRule()

    /**
     * Пульс ожидания — бесконечная анимация, и автоматические часы теста крутили бы её вечно.
     * Часы останавливаются, кадр остаётся — читать с него можно.
     */
    private fun screen(state: PcScreenState) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            PointTheme(darkTheme = true) {
                PairPcScreen(state = state, onPair = { _, _ -> }, onUnpair = {}, onClose = {})
            }
        }
    }

    private val paired = PcPairing(host = "192.168.1.42", port = 8391, token = "t")

    @Test fun `пока Point выясняет связь, экран не утверждает, что её не было (#451)`() {
        // Тот самый экран после перезапуска: компьютер тот же, вчерашний, запрос к нему в пути.
        screen(PcScreenState(pairing = paired, link = LinkState.Checking))

        compose.onNodeWithText("проверяю связь…").assertExists()
        compose.onNodeWithText("ещё не связывались").assertDoesNotExist()
    }

    @Test fun `запомненный вчерашний контакт назван молчанием, а не «ни разу» (#451)`() {
        screen(PcScreenState(pairing = paired, link = LinkState.Silent(20 * 60_000L)))

        compose.onNodeWithText("не отвечает · молчит 20 минут").assertExists()
        compose.onNodeWithText("ещё не связывались").assertDoesNotExist()
    }

    @Test fun `живая связь названа своим словом и путём`() {
        screen(PcScreenState(pairing = paired, link = LinkState.Live(LinkPath.LAN, agoMillis = 3_000)))

        compose.onNodeWithText("на связи · в этой сети").assertExists()
    }

    @Test fun `пока идёт поиск, экран показывает, что ищет (#458)`() {
        // До этого те же секунды выглядели как «ничего нет, вводите руками».
        screen(PcScreenState(search = PcSearch.RUNNING))

        compose.onNodeWithText("Ищу компьютеры в этой сети…").assertExists()
    }

    @Test fun `поиск заканчивается словом, а не тишиной (#458)`() {
        screen(PcScreenState(search = PcSearch.DONE))

        compose.onNodeWithText("В этой сети компьютеров не видно").assertExists()
        compose.onNodeWithText("Ищу компьютеры в этой сети…").assertDoesNotExist()
    }

    @Test fun `нашлось — говорит список, а не строка над ним (#458)`() {
        screen(
            PcScreenState(
                search = PcSearch.RUNNING,
                discovered = listOf(DiscoveredPc(name = "Рабочий ноутбук", host = "192.168.1.42", port = 8391)),
            ),
        )

        compose.onNodeWithText("Рабочий ноутбук").assertExists()
        compose.onNodeWithText("Ищу компьютеры в этой сети…").assertDoesNotExist()
    }
}
