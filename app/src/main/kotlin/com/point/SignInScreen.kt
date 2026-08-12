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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.point.core.flow.DeviceKind
import com.point.core.flow.PointAccount
import com.point.core.flow.SIGN_IN_ACTION
import com.point.core.flow.SIGN_IN_TITLE
import com.point.core.flow.SignIn
import com.point.core.flow.signInWaitingLine
import com.point.core.ui.Outcome
import com.point.core.ui.OutcomeCard
import com.point.core.ui.Portal
import com.point.core.ui.PortalColumnWidth
import com.point.core.ui.PortalRow
import com.point.core.ui.ScreenHeader
import com.point.core.ui.ThinkingDot
import com.point.core.ui.bubbleIcon
import com.point.core.ui.theme.PointTheme

@Composable
fun SignInScreen(
    state: SignIn,
    onSignIn: () -> Unit,
    onCancel: () -> Unit,

    onOpenAgain: (String) -> Unit = {},

    onContinue: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {

        Portal(size = 148.dp, intensity = if (state is SignIn.SignedIn) 1f else 0.55f)
        Spacer(Modifier.height(16.dp))
        ScreenHeader(
            title = if (state is SignIn.SignedIn) "Вы вошли" else SIGN_IN_TITLE,
            // Экран просил действие, не назвав причины: заголовок обещал, что устройства
            // увидят друг друга, но зачем это человеку — оставалось догадкой (#891).
            subtitle = (state as? SignIn.SignedIn)?.account?.email?.takeIf { it.isNotBlank() }
                ?: com.point.core.flow.SIGN_IN_WHY.takeIf { state is SignIn.SignedOut },
            horizontalAlignment = Alignment.CenterHorizontally,
        )
        Spacer(Modifier.height(22.dp))

        Column(
            modifier = Modifier.widthIn(max = PortalColumnWidth).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            when (state) {
                SignIn.SignedOut -> PrimaryAction(SIGN_IN_ACTION, onSignIn)

                is SignIn.Waiting -> {

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(vertical = 6.dp),
                    ) {
                        ThinkingDot()
                        Spacer(Modifier.width(9.dp))
                        Text(
                            signInWaitingLine(state.code),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    PortalRow(
                        title = "Открыть страницу входа снова",
                        onClick = { onOpenAgain(state.url) },
                        icon = bubbleIcon("link"),
                        chevron = false,
                    )
                }

                is SignIn.SignedIn -> {
                    OutcomeCard(
                        title = "Это устройство теперь в вашем круге",
                        detail = "Остальные ваши телефоны и компьютеры видят его сразу — связывать ничего не нужно.",
                        outcome = Outcome.DONE,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    PrimaryAction("Продолжить", onContinue)
                }

                is SignIn.Refused -> {

                    OutcomeCard(
                        title = state.what,
                        detail = state.fix,
                        outcome = Outcome.FAILED,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    PrimaryAction(SIGN_IN_ACTION, onSignIn)
                }
            }
        }

        if (state is SignIn.Waiting) {
            Spacer(Modifier.height(10.dp))
            TextButton(onClick = onCancel) {
                Text("Отменить", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun PrimaryAction(title: String, onClick: () -> Unit) {
    PortalRow(
        title = title,
        onClick = onClick,
        icon = bubbleIcon(ACCOUNT_ICON),
        primary = true,
        chevron = false,
    )
}

internal const val ACCOUNT_ICON = "account"

@Preview(name = "Вход · не вошёл (#472)", showBackground = true, backgroundColor = 0xFF0B0D10)
@Composable
private fun PreviewSignedOut() = PointTheme(darkTheme = true) {

    SignInScreen(state = SignIn.SignedOut, onSignIn = {}, onCancel = {})
}

@Preview(name = "Вход · ждём браузер (#472)", showBackground = true, backgroundColor = 0xFF0B0D10)
@Composable
private fun PreviewWaiting() = PointTheme(darkTheme = true) {

    SignInScreen(
        state = SignIn.Waiting(loginId = "l1", code = "K7-42Q", url = "https://point.leerio.app/login?d=l1"),
        onSignIn = {},
        onCancel = {},
    )
}

@Preview(name = "Вход · вошёл (#472)", showBackground = true, backgroundColor = 0xFF0B0D10)
@Composable
private fun PreviewSignedIn() = PointTheme(darkTheme = true) {

    SignInScreen(
        state = SignIn.SignedIn(
            PointAccount("d1", "tok", "vladimir@example.com", "Pixel 8", DeviceKind.PHONE),
        ),
        onSignIn = {},
        onCancel = {},
    )
}

@Preview(name = "Вход · отказ (#472)", showBackground = true, backgroundColor = 0xFF0B0D10)
@Composable
private fun PreviewRefused() = PointTheme(darkTheme = true) {

    SignInScreen(
        state = SignIn.Refused(
            what = "До сервера Point не дозвониться",
            fix = "Проверьте интернет и попробуйте ещё раз — ничего не потеряно.",
        ),
        onSignIn = {},
        onCancel = {},
    )
}
