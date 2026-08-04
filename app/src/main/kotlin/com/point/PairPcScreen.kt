package com.point

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.point.core.flow.DiscoveredPc
import com.point.core.flow.LinkPath
import com.point.core.flow.LinkState
import com.point.core.flow.PcPairing
import com.point.core.flow.linkLabel
import com.point.core.ui.Outcome
import com.point.core.ui.OutcomeBanner
import com.point.core.ui.Portal
import com.point.core.ui.PortalColumnWidth
import com.point.core.ui.PortalRow
import com.point.core.ui.ScreenHeader
import com.point.core.ui.SectionLabel
import com.point.core.ui.ThinkingDot
import com.point.core.ui.bubbleColor
import com.point.core.ui.bubbleIcon
import com.point.core.ui.theme.PointTheme

/**
 * «Компьютер» (#147): pair the phone with «Point для ПК». Manual host:port entry is
 * the always-working path (the emulator reaches the host PC as 10.0.2.2); the PC
 * confirms with its own dialog, so pairing is a handshake, not a secret exchange.
 *
 * Экран говорит языком портала (#114). У него, как у экрана объекта, есть герой — сам компьютер, —
 * и герой носит портал-кольцо: оно горит в полную силу, когда связь живая, и притушено, когда
 * компьютер молчит или его ещё не искали. Раньше «на связи» и «не отвечает» отличались только
 * цветом одной строчки мелким шрифтом.
 *
 * Найденный в сети компьютер и «Связать» — те же строки дизайн-системы, что действия на экране
 * объекта. Ожидание рукопожатия — тот же пульс, которым «Point думает» (MOTION.md принцип №3),
 * вместо крутилки Material. Отказ — та же карточка исхода, что везде, вместо красного
 * `colorScheme.error`, которым Point не говорит больше нигде.
 */
@Composable
fun PairPcScreen(
    state: PcScreenState,
    onPair: (host: String, port: Int) -> Unit,
    onUnpair: () -> Unit,
    onClose: () -> Unit,
) {
    var host by remember { mutableStateOf(state.pairing?.host ?: "") }
    var port by remember { mutableStateOf((state.pairing?.port ?: 8391).toString()) }
    val canPair = host.isNotBlank() && port.toIntOrNull() != null

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Кольцо — и есть ответ на вопрос «а он вообще там?»: горит связь, тускнеет молчание.
        Portal(size = 148.dp, intensity = portalIntensity(state))
        Spacer(Modifier.height(16.dp))
        ScreenHeader(
            title = "Компьютер",
            subtitle = if (state.pairing != null) {
                // «Подключён» раньше означало только «мы когда-то познакомились» — адрес из
                // пейринга. Теперь рядом живое состояние: отвечает компьютер или молчит (#412).
                "${state.pairing.host}:${state.pairing.port}"
            } else {
                "Запустите «Point для ПК» и введите адрес из его окна"
            },
            horizontalAlignment = Alignment.CenterHorizontally,
        )
        if (state.pairing != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                linkLabel(state.link),
                style = MaterialTheme.typography.bodyMedium,
                color = when (state.link) {
                    is LinkState.Live -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }

        // Отказ говорит той же карточкой, что и на экране объекта: «не удалось связаться» — такой
        // же исход действия, как «не получилось прочитать».
        OutcomeBanner(state.error, Outcome.FAILED)

        Spacer(Modifier.height(24.dp))

        Column(
            modifier = Modifier.widthIn(max = PortalColumnWidth).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            if (state.discovered.isNotEmpty()) {
                SectionLabel("Найдено в сети")
                state.discovered.forEachIndexed { index, pc ->
                    PortalRow(
                        title = pc.name,
                        subtitle = "${pc.host}:${pc.port}",
                        onClick = { onPair(pc.host, pc.port) },
                        icon = bubbleIcon(PC_ICON),
                        accent = bubbleColor(PC_ICON),
                        appearIndex = index,
                    )
                }
                Spacer(Modifier.height(8.dp))
                SectionLabel("Вручную")
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it.trim() },
                    label = { Text("Адрес") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(10.dp))
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it.filter(Char::isDigit) },
                    label = { Text("Порт") },
                    singleLine = true,
                    modifier = Modifier.width(110.dp),
                )
            }

            if (state.busy) {
                // Рукопожатие ждут тем же пульсом, каким Point думает над объектом, — не крутилкой.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(vertical = 6.dp),
                ) {
                    ThinkingDot()
                    Spacer(Modifier.width(9.dp))
                    Text(
                        "Подтвердите на компьютере…",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                // «Основное действие» экрана — та же светящаяся строка, что главное действие
                // объекта. Пока связывать нечем, она притушена: строка светится, когда может.
                PortalRow(
                    title = "Связать",
                    onClick = { port.toIntOrNull()?.let { onPair(host, it) } },
                    icon = bubbleIcon(PC_ICON),
                    primary = true,
                    chevron = false,
                    enabled = canPair,
                    modifier = Modifier.graphicsLayer { alpha = if (canPair) 1f else 0.45f },
                )
            }
        }

        Spacer(Modifier.height(18.dp))
        // Тихие действия — теми же текстовыми кнопками, что «Отмена» на экране объекта. Рамка
        // Material вокруг «Отвязать» рисовалась цветом ГРАНИЦЫ по ФОНУ и была почти не видна:
        // кнопка выглядела сломанной, а не тихой.
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (state.pairing != null) {
                TextButton(onClick = onUnpair) {
                    Text("Отвязать", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            TextButton(onClick = onClose) {
                Text("Закрыть", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Иконка компьютера из общего словаря — та же, что у действия «На компьютер». */
private const val PC_ICON = "pc"

/** Насколько ярко горит кольцо: живая связь — в полную силу, рукопожатие — тоже, молчание — вполсилы. */
private fun portalIntensity(state: PcScreenState): Float = when {
    state.busy -> 1f
    state.link is LinkState.Live -> 1f
    else -> 0.4f
}

@Preview(name = "Компьютер · на связи (#114)", showBackground = true, backgroundColor = 0xFF0B0D10)
@Composable
private fun PreviewPcLinked() = PointTheme(darkTheme = true) {
    PairPcScreen(
        state = PcScreenState(
            pairing = PcPairing(host = "192.168.1.42", port = 8391, token = "t"),
            link = LinkState.Live(LinkPath.LAN, agoMillis = 4_000),
        ),
        onPair = { _, _ -> },
        onUnpair = {},
        onClose = {},
    )
}

@Preview(name = "Компьютер · ещё не связывались (#114)", showBackground = true, backgroundColor = 0xFF0B0D10)
@Composable
private fun PreviewPcDiscovered() = PointTheme(darkTheme = true) {
    // То, что человек видит в первый раз: кольцо притушено (связи нет), в сети нашлись два
    // компьютера — строками дизайн-системы, а не рядом одинаковых Material-кнопок.
    PairPcScreen(
        state = PcScreenState(
            discovered = listOf(
                DiscoveredPc(name = "Рабочий ноутбук", host = "192.168.1.42", port = 8391),
                DiscoveredPc(name = "Домашний ПК", host = "192.168.1.77", port = 8391),
            ),
        ),
        onPair = { _, _ -> },
        onUnpair = {},
        onClose = {},
    )
}

@Preview(name = "Компьютер · не получилось (#114)", showBackground = true, backgroundColor = 0xFF0B0D10)
@Composable
private fun PreviewPcFailed() = PointTheme(darkTheme = true) {
    // Отказ той же карточкой исхода, что на экране объекта. Раньше здесь стоял красный
    // `colorScheme.error` — чужой голос, которым Point не говорит больше нигде.
    PairPcScreen(
        state = PcScreenState(error = "Компьютер не ответил — проверьте, что «Point для ПК» запущен"),
        onPair = { _, _ -> },
        onUnpair = {},
        onClose = {},
    )
}
