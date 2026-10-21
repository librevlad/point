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
import com.point.core.model.tr
import com.point.core.ui.Outcome
import com.point.core.ui.OutcomeBanner
import com.point.core.ui.PortalColumnWidth
import com.point.core.ui.PortalRow
import com.point.core.ui.SectionLabel
import com.point.core.ui.ThinkingDot
import com.point.core.ui.bubbleColor
import com.point.core.ui.bubbleIcon
import com.point.core.ui.theme.PointTheme
import com.point.core.ui.ScreenTopBar
import com.point.core.ui.GroupHeader
import com.point.core.ui.GlassGroup
import com.point.core.ui.GroupSeam
import com.point.core.ui.GlassButton
import com.point.core.ui.SmallPill
import com.point.core.ui.PortalPlate
import com.point.core.ui.PointPalette
import com.point.core.ui.theme.PointText
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.text.style.TextOverflow

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
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(modifier = Modifier.widthIn(max = PortalColumnWidth).fillMaxWidth()) {

            // Шапка макета «Мои устройства · телефон»: назад и имя экрана в одной строке.
            ScreenTopBar(title = MY_DEVICES_TITLE, onBack = onClose, backLabel = LEAVE_BACK)
            state.email.takeIf { it.isNotBlank() }?.let { email ->
                Text(email, style = PointText.note, modifier = Modifier.padding(start = 16.dp, top = 10.dp))
            }

            OutcomeBanner(state.error, Outcome.FAILED)

            if (state.loading) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(start = 16.dp, top = 18.dp),
                ) {
                    ThinkingDot()
                    Spacer(Modifier.width(9.dp))
                    Text(tr("Спрашиваю сервер о ваших устройствах…"), style = PointText.note)
                }
            }

            if (state.devices.isNotEmpty()) {
                GroupHeader(tr("Круг · {0}", state.devices.size))
                GlassGroup {
                    state.devices.forEachIndexed { index, device ->
                        if (index > 0) GroupSeam(start = 12.dp + 40.dp + 14.dp)
                        DeviceRow(
                            device = device,

                            // Правило второй строки общее с компьютером (#891). «На связи»
                            // утверждается только для списка, который сервер отдал только что;
                            // запомненный круг — нет (#1076). Свежесть — свойство списка, не
                            // отсутствия ошибки: ошибка гаснет с началом любой операции, а список
                            // при этом тот же.
                            line = com.point.core.flow.deviceLine(device, now, checkedNow = state.checkedNow),
                            busy = state.busy,
                            onRevoke = { onRevoke(device.id) },
                        )
                    }
                }
            }

            if (knockOff && others.any { it.kind == com.point.core.flow.DeviceKind.PC }) {
                Spacer(Modifier.height(14.dp))
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
                SectionLabel(tr("Пока вы один"), modifier = Modifier.padding(start = 16.dp, top = 18.dp, bottom = 6.dp))
                Text(
                    tr("Запустите «Point для ПК» и войдите в тот же аккаунт — компьютер появится здесь " +
                        "сам, связывать его ничем не нужно."),
                    style = PointText.note,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }

            Spacer(Modifier.height(22.dp))
            GlassButton(tr("Выйти"), onClick = onSignOut, enabled = !state.busy)

            Spacer(Modifier.height(14.dp))
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (!confirmingDelete) {
                    TextButton(onClick = { confirmingDelete = true }, enabled = !state.busy) {
                        Text(DELETE_ACCOUNT, style = PointText.note.copy(color = PointPalette.warmText))
                    }
                } else {
                    Text(
                        DELETE_ACCOUNT_WHAT,
                        style = PointText.note,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        TextButton(onClick = { confirmingDelete = false }) {
                            Text(tr("Не удалять"), style = PointText.note)
                        }
                        TextButton(onClick = onDeleteAccount, enabled = !state.busy) {
                            Text(DELETE_ACCOUNT_CONFIRM, style = PointText.note.copy(color = PointPalette.warmText))
                        }
                    }
                }
            }
        }
    }
}

/**
 * Устройство круга: знак вида в кольце его цвета — телефон зелёный, компьютер синий, как точки
 * связи у окна компьютера, — имя, вторая строка о связи и «Отключить».
 */
@Composable
private fun DeviceRow(device: CircleDevice, line: String, busy: Boolean, onRevoke: () -> Unit) {
    val key = com.point.core.flow.deviceIconKey(device.kind)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .padding(start = 12.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        PortalPlate(
            accent = bubbleColor(key),
            icon = bubbleIcon(key),
            size = 40.dp,
            tint = false,
            ring = (if (device.kind == com.point.core.flow.DeviceKind.PC) PointPalette.blue else PointPalette.green).copy(alpha = 0.8f),
        )
        Column(Modifier.weight(1f)) {
            Text(device.name, style = PointText.rowTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(line, style = PointText.note, modifier = Modifier.padding(top = 2.dp))
        }
        SmallPill(tr("Отключить"), onClick = onRevoke, warm = true, enabled = !busy)
    }
}

internal val DELETE_ACCOUNT: String get() = tr("Удалить аккаунт")

internal const val DELETE_ACCOUNT_WHAT =
    tr("Исчезнут: аккаунт Point, все ваши устройства в круге и всё, что лежит на сервере " +
        "недоставленным. Объекты на самих устройствах останутся. Отменить будет нельзя.")

internal val DELETE_ACCOUNT_CONFIRM: String get() = tr("Удалить навсегда")

private const val NOW = 1_800_000_000_000L

@Preview(name = "Мои устройства · круг из трёх (#472)", showBackground = true, backgroundColor = 0xFF2B2531)
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

@Preview(name = "Мои устройства · пока один (#472)", showBackground = true, backgroundColor = 0xFF2B2531)
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

@Preview(name = "Мои устройства · круг едет (#472)", showBackground = true, backgroundColor = 0xFF2B2531)
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

@Preview(name = "Мои устройства · сервер молчит (#472)", showBackground = true, backgroundColor = 0xFF2B2531)
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

@Preview(name = "Мои устройства · сервер молчит, круг по памяти (#1076)", showBackground = true, backgroundColor = 0xFF2B2531)
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
private val KNOCK_TITLE: String get() = tr("Сообщать о просьбах компьютера")
private val KNOCK_WHAT: String get() = tr("Сейчас просьба ждёт, пока вы откроете Point сами")
