package com.point.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.point.core.flow.CircleDevice
import com.point.core.flow.DeviceKind
import com.point.core.flow.MY_DEVICES_TITLE
import com.point.core.flow.SIGN_IN_ACTION
import com.point.core.flow.SIGN_IN_PRIVACY
import com.point.core.flow.SIGN_IN_TITLE
import com.point.core.flow.SignIn
import com.point.core.flow.deviceKindLabel
import com.point.core.flow.lastSeenLabel
import com.point.core.flow.signInWaitingLine

/**
 * Вход на компьютере (#473) — **тот же поток и те же слова**, что на телефоне.
 *
 * Ни своей схемы возврата, ни своего слушателя: окно открывает системный браузер и опрашивает
 * сервер, а пропуск приезжает готовым. Тексты — общие константы `:core:flow`: два экрана об одном
 * обязаны говорить одно, иначе они разъедутся молча.
 *
 * Экран занимает окно целиком, потому что компьютер без круга и раньше ничего не делал — он
 * стартовал карточкой «подключите телефон». Вход занял её место.
 */
@Composable
fun SignInPane(
    state: SignIn,
    onSignIn: () -> Unit,
    onCancel: () -> Unit,
    onOpenAgain: (String) -> Unit,
    onContinue: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
    ) {
        PortalRing(bright = state is SignIn.SignedIn)
        Text(
            if (state is SignIn.SignedIn) "Вы вошли" else SIGN_IN_TITLE,
            style = PointType.display,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(560.dp),
        )
        when (state) {
            SignIn.SignedOut -> GlowAction(SIGN_IN_ACTION, onSignIn)

            is SignIn.Waiting -> {
                Text(signInWaitingLine(state.code), style = PointType.body)
                QuietAction("Открыть страницу входа снова") { onOpenAgain(state.url) }
                QuietAction("Отменить", onClick = onCancel)
            }

            is SignIn.SignedIn -> {
                Text(state.account.email, style = PointType.body)
                // Здесь стояла неправда (#524): «телефон видит его сразу» — при том что телефон
                // узнавал про компьютер только при совпадении трёх условий разом, а вне общей сети не
                // узнавал никогда. Теперь сказано то, что есть на самом деле: второе устройство обязано
                // войти в тот же аккаунт, и Point на нём обязан быть запущен, когда ему пишут.
                Text(
                    "Этот компьютер теперь в вашем круге. Войдите в тот же аккаунт на телефоне — и они найдут " +
                        "друг друга сами. Чтобы забрать присланное, Point на этом компьютере должен быть запущен.",
                    style = PointType.small,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.width(460.dp),
                )
                GlowAction("Продолжить", onContinue)
            }

            is SignIn.Refused -> {
                Text(state.what, style = PointType.title)
                Text(
                    state.fix,
                    style = PointType.small,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.width(460.dp),
                )
                GlowAction(SIGN_IN_ACTION, onSignIn)
            }
        }
        Text(
            SIGN_IN_PRIVACY,
            style = PointType.small,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(520.dp),
        )
    }
}

/**
 * «Мои устройства» на компьютере — тот же круг, что видит телефон.
 *
 * Заменил карточку с QR: связывать больше нечего, показывать нужно то, что у человека есть.
 */
@Composable
fun MyDevicesPane(
    email: String,
    devices: List<CircleDevice>,
    busy: Boolean,
    error: String?,
    onRevoke: (String) -> Unit,
    onSignOut: () -> Unit,
    now: Long = System.currentTimeMillis(),
) {
    Column(
        modifier = Modifier.width(380.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(MY_DEVICES_TITLE.uppercase(), style = PointType.label)
        if (email.isNotBlank()) Text(email, style = PointType.small)
        error?.let { Text(it, style = PointType.small.copy(color = PointColors.violet)) }
        devices.forEach { device ->
            Row(
                modifier = Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Brush.verticalGradient(listOf(PointColors.surface, PointColors.surfaceDeep)))
                    .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(14.dp))
                    .padding(horizontal = 15.dp, vertical = 11.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(8.dp).background(
                        if (device.kind == DeviceKind.PC) PointColors.cyan else PointColors.violet,
                        CircleShape,
                    ),
                )
                Column(Modifier.weight(1f)) {
                    Text(device.name, style = PointType.body)
                    Text(
                        listOfNotNull(
                            "это устройство".takeIf { device.self },
                            deviceKindLabel(device.kind),
                            lastSeenLabel(device.lastSeenMillis, now),
                        ).joinToString(" · "),
                        style = PointType.small,
                    )
                }
                QuietAction("Отключить", enabled = !busy) { onRevoke(device.id) }
            }
        }
        if (devices.size <= 1) {
            Text(
                "Пока вы один. Войдите в тот же аккаунт на телефоне — он появится здесь сам.",
                style = PointType.small,
            )
        }
        QuietAction("Выйти", enabled = !busy, onClick = onSignOut)
    }
}

/** Кольцо портала — тот же знак, что на телефоне: горит, когда связь есть. */
@Composable
private fun PortalRing(bright: Boolean) {
    val alpha = if (bright) 0.85f else 0.45f
    Box(Modifier.size(150.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(150.dp)
                .background(
                    Brush.radialGradient(listOf(PointColors.violet.copy(alpha = alpha * 0.25f), Color.Transparent)),
                    CircleShape,
                )
                .border(2.dp, PointColors.violet.copy(alpha = alpha), CircleShape),
        )
        Box(Modifier.size(128.dp).border(1.dp, PointColors.cyan.copy(alpha = alpha * 0.4f), CircleShape))
    }
}

/** Основное действие — светящаяся строка, как на телефоне. */
@Composable
private fun GlowAction(title: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier.width(320.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Brush.horizontalGradient(listOf(Color(0xFF7B5CFF), Color(0xFF4E7BFF))))
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(title, style = PointType.body.copy(color = Color.White))
    }
}

/** Тихое действие — текстом, без рамки: то же, чем на телефоне говорит «Отмена». */
@Composable
private fun QuietAction(title: String, enabled: Boolean = true, onClick: () -> Unit) {
    Text(
        title,
        style = PointType.small.copy(color = if (enabled) PointColors.muted else PointColors.border),
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .let { if (enabled) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}
