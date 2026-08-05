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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.point.core.flow.CircleDevice
import com.point.core.flow.DeviceKind
import com.point.core.flow.MY_DEVICES_TITLE
import com.point.core.flow.deviceKindLabel
import com.point.core.flow.lastSeenLabel
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
 * «Мои устройства» (#472) — тот же экран, что был экраном компьютера, и в этом вся мысль.
 *
 * Пейринга больше нет как действия: устройство, вошедшее в аккаунт, само оказывается в круге и
 * сразу видит остальные. Поэтому здесь нет ни QR, ни поля «адрес», ни «Связать» — есть список того,
 * что у человека есть, и возможность отключить лишнее. Новый раздел при этом не заводится: экран
 * отвечает на тот же вопрос, что и прежний экран компьютера, — «что у меня за компьютер и на связи
 * ли он».
 *
 * С #544 в него входят из настроек, а не отдельной дверью «Недавнего»: экран переехал как есть, ни
 * одна его мысль не менялась — изменилось только место, откуда сюда попадают, и то, куда ведёт
 * «Закрыть» (обратно в настройки, а не на «Недавнее»).
 *
 * Строки — те же строки действия, что на экране объекта; отказ — та же карточка исхода. Отдельного
 * языка у экрана устройств нет.
 */
@Composable
fun MyDevicesScreen(
    state: DevicesScreenState,
    onRevoke: (String) -> Unit,
    onSignOut: () -> Unit,
    onClose: () -> Unit,
    /**
     * «Удалить аккаунт» — учётная запись, круг и все байты сервера, необратимо.
     *
     * Отдельно от «Выйти» по существу: выход снимает это устройство и оставляет аккаунт на месте.
     * Пока такой двери не было, человек, решивший уйти совсем, мог только выйти — а его почта,
     * круг и невыбранные письма продолжали лежать на сервере.
     */
    onDeleteAccount: () -> Unit = {},
    /** «Сейчас» отдельным параметром, чтобы «на связи» и «вчера» можно было посудить тестом. */
    now: Long = System.currentTimeMillis(),
) {
    // Необратимое действие спрашивает один раз — и спрашивает СВОИМИ словами, перечисляя, что
    // исчезнет. «Вы уверены?» не сообщает ничего: уверен человек всегда, пока не прочитал, о чём.
    var confirmingDelete by rememberSaveable { mutableStateOf(false) }
    val others = state.devices.filterNot { it.self }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Кольцо — ответ на «а есть ли там кто-то ещё?»: горит, когда круг не пуст.
        Portal(size = 148.dp, intensity = if (others.isEmpty()) 0.4f else 1f)
        Spacer(Modifier.height(16.dp))
        ScreenHeader(
            title = MY_DEVICES_TITLE,
            subtitle = state.email.takeIf { it.isNotBlank() },
            horizontalAlignment = Alignment.CenterHorizontally,
        )

        OutcomeBanner(state.error, Outcome.FAILED)

        Spacer(Modifier.height(22.dp))

        Column(
            modifier = Modifier.widthIn(max = PortalColumnWidth).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            // Пока круг едет с сервера — пульс со словом, а не пустой экран, который неотличим от
            // «у вас ничего нет».
            if (state.loading) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(vertical = 6.dp),
                ) {
                    ThinkingDot()
                    Spacer(Modifier.width(9.dp))
                    Text(
                        "Спрашиваю сервер о ваших устройствах…",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            state.devices.forEachIndexed { index, device ->
                val icon = if (device.kind == DeviceKind.PC) "pc" else "phone"
                PortalRow(
                    title = device.name,
                    // Вид и время последнего контакта — одной строкой: «Компьютер · на связи».
                    // «Это устройство» стоит первым, чтобы человек не отключил то, что держит в руках.
                    subtitle = listOfNotNull(
                        "это устройство".takeIf { device.self },
                        deviceKindLabel(device.kind),
                        lastSeenLabel(device.lastSeenMillis, now),
                    ).joinToString(" · "),
                    // Строка круга — не кнопка, а запись: тапать по ней нечем, и делать вид, что
                    // есть, нельзя (#464 — «тап по готовой строке делает то, что на ней написано»).
                    // Действие у неё одно, и оно названо словом справа.
                    onClick = { },
                    enabled = false,
                    icon = bubbleIcon(icon),
                    accent = bubbleColor(icon),
                    chevron = false,
                    appearIndex = index,
                    trailing = {
                        // Отключить можно любое, включая своё: телефон теряют, а отзывать его надо с
                        // того, что осталось в руках. Своё при этом названо своим — тап без имени
                        // был бы выходом вслепую.
                        TextButton(onClick = { onRevoke(device.id) }, enabled = !state.busy) {
                            Text(
                                "Отключить",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                    },
                )
            }

            if (!state.loading && others.isEmpty()) {
                Spacer(Modifier.height(6.dp))
                SectionLabel("Пока вы один")
                Text(
                    "Запустите «Point для ПК» и войдите в тот же аккаунт — компьютер появится здесь " +
                        "сам, связывать его ничем не нужно.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(18.dp))
        // Тихие действия — теми же текстовыми кнопками, что «Отмена» на экране объекта.
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TextButton(onClick = onSignOut, enabled = !state.busy) {
                Text("Выйти", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = onClose) {
                Text("Закрыть", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Spacer(Modifier.height(22.dp))
        Column(
            modifier = Modifier.widthIn(max = PortalColumnWidth).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (!confirmingDelete) {
                TextButton(onClick = { confirmingDelete = true }, enabled = !state.busy) {
                    Text(DELETE_ACCOUNT, color = MaterialTheme.colorScheme.error)
                }
            } else {
                Text(
                    DELETE_ACCOUNT_WHAT,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TextButton(onClick = { confirmingDelete = false }) {
                        Text("Не удалять", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = onDeleteAccount, enabled = !state.busy) {
                        Text(DELETE_ACCOUNT_CONFIRM, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

/** Дверь удаления — своими словами, а не «управление аккаунтом». */
internal const val DELETE_ACCOUNT = "Удалить аккаунт"

/** Что именно исчезнет. Перечислено поимённо: обобщение здесь читается как «что-то удалим». */
internal const val DELETE_ACCOUNT_WHAT =
    "Исчезнут: аккаунт Point, все ваши устройства в круге и всё, что лежит на сервере " +
        "недоставленным. Объекты на самих устройствах останутся. Отменить будет нельзя."

internal const val DELETE_ACCOUNT_CONFIRM = "Удалить навсегда"

private const val NOW = 1_800_000_000_000L

@Preview(name = "Мои устройства · круг из трёх (#472)", showBackground = true, backgroundColor = 0xFF0B0D10)
@Composable
private fun PreviewCircle() = PointTheme(darkTheme = true) {
    MyDevicesScreen(
        state = DevicesScreenState(
            email = "vladimir@example.com",
            loading = false,
            devices = listOf(
                CircleDevice("d1", DeviceKind.PHONE, "Pixel 8", NOW - 20_000, self = true),
                CircleDevice("d2", DeviceKind.PC, "Рабочий ноутбук", NOW - 40_000),
                CircleDevice("d3", DeviceKind.PC, "Домашний ПК", NOW - 30 * 60 * 60_000L),
            ),
        ),
        onRevoke = {},
        onSignOut = {},
        onClose = {},
        now = NOW,
    )
}

@Preview(name = "Мои устройства · пока один (#472)", showBackground = true, backgroundColor = 0xFF0B0D10)
@Composable
private fun PreviewAlone() = PointTheme(darkTheme = true) {
    // То, что человек видит сразу после входа: кольцо приглушено, и сказано, чем это лечится.
    MyDevicesScreen(
        state = DevicesScreenState(
            email = "vladimir@example.com",
            loading = false,
            devices = listOf(CircleDevice("d1", DeviceKind.PHONE, "Pixel 8", NOW, self = true)),
        ),
        onRevoke = {},
        onSignOut = {},
        onClose = {},
        now = NOW,
    )
}

@Preview(name = "Мои устройства · круг едет (#472)", showBackground = true, backgroundColor = 0xFF0B0D10)
@Composable
private fun PreviewLoading() = PointTheme(darkTheme = true) {
    MyDevicesScreen(
        state = DevicesScreenState(email = "vladimir@example.com", loading = true),
        onRevoke = {},
        onSignOut = {},
        onClose = {},
        now = NOW,
    )
}

@Preview(name = "Мои устройства · сервер молчит (#472)", showBackground = true, backgroundColor = 0xFF0B0D10)
@Composable
private fun PreviewFailed() = PointTheme(darkTheme = true) {
    // Круг не приехал — и об этом сказано той же карточкой исхода, что и о любом другом отказе.
    // Устройство, которое Point помнит, при этом остаётся на экране: молчание сервера не отменяет
    // того, что человек знает про себя.
    MyDevicesScreen(
        state = DevicesScreenState(
            email = "vladimir@example.com",
            loading = false,
            error = "Не удалось спросить сервер о ваших устройствах — проверьте интернет",
            devices = listOf(CircleDevice("d1", DeviceKind.PHONE, "Pixel 8", NOW, self = true)),
        ),
        onRevoke = {},
        onSignOut = {},
        onClose = {},
        now = NOW,
    )
}
