package com.point.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.point.core.flow.CircleDevice
import com.point.core.flow.SIGN_IN_ACTION
import com.point.core.flow.SIGN_IN_TITLE
import com.point.core.flow.SignIn
import com.point.core.flow.signInWaitingLine

/** Вход в аккаунт на компьютере: кольцо Point, слова и одна светлая кнопка шага. */
@Composable
fun SignInPane(
    state: SignIn,
    onSignIn: () -> Unit,
    onCancel: () -> Unit,
    onOpenAgain: (String) -> Unit,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterVertically),
    ) {
        PortalRing(bright = state is SignIn.SignedIn)
        Text(
            if (state is SignIn.SignedIn) "Вы вошли" else SIGN_IN_TITLE,
            style = PointType.display,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 460.dp),
        )
        when (state) {
            SignIn.SignedOut -> {
                Explain(com.point.core.flow.SIGN_IN_WHY)
                LightButton(SIGN_IN_ACTION, onSignIn)
            }

            is SignIn.Waiting -> {
                Text(signInWaitingLine(state.code), style = PointType.body, textAlign = TextAlign.Center)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PillButton("Открыть страницу входа снова", { onOpenAgain(state.url) })
                    PillButton("Отменить", onCancel)
                }
            }

            is SignIn.SignedIn -> {
                Text(state.account.email, style = PointType.body)
                Explain(
                    "Этот компьютер теперь в вашем круге. Войдите в тот же аккаунт на телефоне — и они найдут " +
                        "друг друга сами. Чтобы забрать присланное, Point на этом компьютере должен быть запущен.",
                )
                LightButton("Продолжить", onContinue)
            }

            is SignIn.Refused -> {
                Text(state.what, style = PointType.title, textAlign = TextAlign.Center)
                Explain(state.fix)
                LightButton(SIGN_IN_ACTION, onSignIn)
            }
        }
    }
}

@Composable
private fun Explain(text: String) {
    Text(text, style = PointType.small, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 420.dp))
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
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Имя экрана говорит шапка — второй раз оно здесь только шумит (#886, тот же класс,
        // что двойное «Настройки» из #878).
        if (email.isNotBlank()) Text(email, style = PointType.small, modifier = Modifier.padding(start = 10.dp))
        error?.let { Text(it, style = PointType.small.copy(color = PointColors.close), modifier = Modifier.padding(start = 10.dp)) }
        if (devices.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().glassPanel().padding(4.dp)) {
                devices.forEach { device ->
                    // Значок вида, а не цветная точка: точка ничего не называет, и человеку
                    // приходилось догадываться, что голубая — компьютер (#891). Значки —
                    // из общей с телефоном таблицы.
                    val key = com.point.core.flow.deviceIconKey(device.kind)
                    GlassRow(
                        title = device.name,
                        subtitle = com.point.core.flow.deviceLine(device, now),
                        icon = lineIcon(key),
                        tint = actionTint(com.point.core.ui.bubbleColor(key)),
                        onClick = null,
                    ) {
                        PillButton("Отключить", { onRevoke(device.id) }, enabled = !busy, small = true)
                    }
                }
            }
        }
        if (devices.size <= 1) {
            Text(
                "Пока вы один. Войдите в тот же аккаунт на телефоне — он появится здесь сам.",
                style = PointType.small,
                modifier = Modifier.padding(start = 10.dp),
            )
        }
        PillButton("Выйти", onSignOut, enabled = !busy, modifier = Modifier.padding(start = 6.dp, top = 4.dp))
    }
}

/**
 * Знак Point над входом — наш портал, как на иконке приложения: тусклый, пока вход не случился,
 * яркий — когда компьютер уже в круге.
 */
@Composable
private fun PortalRing(bright: Boolean) {
    PointGlyph(Modifier.alpha(if (bright) 1f else 0.6f), size = 120.dp, field = true)
}
