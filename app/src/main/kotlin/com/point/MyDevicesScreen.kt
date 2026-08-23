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

@Composable
fun MyDevicesScreen(
    state: DevicesScreenState,
    onRevoke: (String) -> Unit,
    onSignOut: () -> Unit,
    onClose: () -> Unit,

    onDeleteAccount: () -> Unit = {},

    /**
     * Стучать ли, когда компьютер о чём-то просит (#817).
     *
     * Спрашивается здесь, а не на старте: до того, как в круге появился компьютер, стучать
     * некому, и разрешение просилось бы ни за чем.
     */
    knockOff: Boolean = false,
    onAllowKnock: () -> Unit = {},

    now: Long = System.currentTimeMillis(),
) {

    var confirmingDelete by rememberSaveable { mutableStateOf(false) }
    val others = state.devices.filterNot { it.self }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {

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
                val icon = com.point.core.flow.deviceIconKey(device.kind)
                PortalRow(
                    title = device.name,

                    // Правило второй строки общее с компьютером (#891). «На связи» утверждается
                    // только для списка, который сервер отдал только что; запомненный круг —
                    // нет (#1076). Свежесть — свойство списка, не отсутствия ошибки: ошибка
                    // гаснет с началом любой операции, а список при этом тот же.
                    subtitle = com.point.core.flow.deviceLine(device, now, checkedNow = state.checkedNow),

                    onClick = { },
                    enabled = false,
                    icon = bubbleIcon(icon),
                    accent = bubbleColor(icon),
                    chevron = false,
                    appearIndex = index,
                    trailing = {

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

            if (knockOff && others.any { it.kind == com.point.core.flow.DeviceKind.PC }) {
                Spacer(Modifier.height(6.dp))
                PortalRow(
                    title = KNOCK_TITLE,
                    subtitle = KNOCK_WHAT,
                    onClick = onAllowKnock,
                    icon = bubbleIcon("pc"),
                    accent = bubbleColor("pc"),
                )
            }

            // «Пока вы один» — только когда круга не было никогда либо последний известный
            // круг и правда из одного этого устройства: при молчании сервера сюда приходит
            // запомненный круг, и незнание больше не выдаётся за одиночество (#1076).
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

internal const val DELETE_ACCOUNT = "Удалить аккаунт"

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
            checkedNow = true,
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

@Preview(name = "Мои устройства · сервер молчит, круг по памяти (#1076)", showBackground = true, backgroundColor = 0xFF0B0D10)
@Composable
private fun PreviewFailedRemembered() = PointTheme(darkTheme = true) {

    MyDevicesScreen(
        state = DevicesScreenState(
            email = "vladimir@example.com",
            loading = false,
            error = "Не удалось спросить сервер о ваших устройствах — проверьте интернет",
            devices = listOf(
                CircleDevice("d1", DeviceKind.PHONE, "Pixel 8", NOW - 20_000, self = true),
                CircleDevice("d2", DeviceKind.PC, "Рабочий ноутбук", NOW - 40_000),
            ),
        ),
        onRevoke = {},
        onSignOut = {},
        onClose = {},
        now = NOW,
    )
}

/** Разрешение спрашивается словами человека: что он получит, а не как это устроено. */
private const val KNOCK_TITLE = "Сообщать о просьбах компьютера"
private const val KNOCK_WHAT = "Сейчас просьба ждёт, пока вы откроете Point сами"
