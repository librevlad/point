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
import com.point.core.flow.SIGN_IN_ACTION
import com.point.core.flow.SIGN_IN_TITLE
import com.point.core.flow.SignIn
import com.point.core.flow.deviceKindLabel
import com.point.core.flow.lastSeenLabel
import com.point.core.flow.signInWaitingLine

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
            SignIn.SignedOut -> {
                Text(
                    com.point.core.flow.SIGN_IN_WHY,
                    style = PointType.small,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.width(460.dp),
                )
                GlowAction(SIGN_IN_ACTION, onSignIn)
            }

            is SignIn.Waiting -> {
                Text(signInWaitingLine(state.code), style = PointType.body)
                QuietAction("Открыть страницу входа снова") { onOpenAgain(state.url) }
                QuietAction("Отменить", onClick = onCancel)
            }

            is SignIn.SignedIn -> {
                Text(state.account.email, style = PointType.body)

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

    }
}

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
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Имя экрана говорит шапка окна — второй раз оно здесь только шумит (#886, тот же
        // класс, что двойное «Настройки» из #878).
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
                // Значок вида, а не цветная точка: точка ничего не называет, и человеку
                // приходилось догадываться, что голубая — компьютер (#891). Значки —
                // из общей с телефоном таблицы.
                PortalPlate(
                    accent = com.point.core.ui.bubbleColor(com.point.core.flow.deviceIconKey(device.kind)),
                    icon = com.point.core.ui.bubbleIcon(com.point.core.flow.deviceIconKey(device.kind)),
                    size = 34.dp,
                )
                Column(Modifier.weight(1f)) {
                    Text(device.name, style = PointType.body)
                    Text(com.point.core.flow.deviceLine(device, now), style = PointType.small)
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
