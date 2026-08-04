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
import com.point.core.flow.SIGN_IN_PRIVACY
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

/**
 * Вход (#472) — единственный новый экран за всю работу, и он **заменяет** собой экран пейринга, а
 * не добавляется к нему.
 *
 * Точка входа стоит до объекта: Point узнаёт устройства человека по аккаунту, и пока он не назван,
 * круга нет. Экран собран из того же, из чего собран экран объекта, — портал, светящаяся строка,
 * карточка исхода, — потому что вход не заводит своей предметной области и не превращает Point в
 * «ещё одно приложение с логином». Ни полей, ни «регистрации», ни второго согласия: согласие на
 * облако живёт в `ConsentScreen`, и второго Point не спрашивает.
 *
 * **Устройство не держит учётных данных Google вовсе.** Разговаривает с Google наш сервер; сюда
 * возвращается только собственный пропуск устройства. Поэтому здесь нет и не может быть поля пароля
 * — и нечего забыть в раздаваемом артефакте.
 *
 * Пока человек в браузере, экран говорит код и держит выход: «Подтвердите вход в браузере · код
 * K7-42Q» и «Отменить» — а не крутящееся колесо без двери («из любого состояния есть выход», #114).
 */
@Composable
fun SignInScreen(
    state: SignIn,
    onSignIn: () -> Unit,
    onCancel: () -> Unit,
    /** Открыть страницу входа ещё раз — браузер могли закрыть, не дойдя до конца. */
    onOpenAgain: (String) -> Unit = {},
    /** Уйти с экрана после удачного входа: дальше начинается объект. */
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
        // Кольцо горит в полную силу, когда вход состоялся, и приглушено, пока нет: тот же язык,
        // что у экрана компьютера, — портал светится, когда связь есть.
        Portal(size = 148.dp, intensity = if (state is SignIn.SignedIn) 1f else 0.55f)
        Spacer(Modifier.height(16.dp))
        ScreenHeader(
            title = if (state is SignIn.SignedIn) "Вы вошли" else SIGN_IN_TITLE,
            subtitle = (state as? SignIn.SignedIn)?.account?.email?.takeIf { it.isNotBlank() },
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
                    // Ждём тем же пульсом, каким Point думает над объектом (MOTION.md принцип №3).
                    // Код виден рядом с ожиданием, а не в справке: сверить его — работа человека, и
                    // она названа там, где выполняется.
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
                    // Браузер закрывают, не дойдя до конца, — и тогда ждать нечего. Дверь обратно
                    // на страницу стоит рядом с ожиданием, а не прячется за отменой.
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
                    // Отказ той же карточкой исхода, что везде, и с продолжением: «ошибка» без
                    // совета оставляет человека там же, откуда он пришёл (#465).
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

        Spacer(Modifier.height(18.dp))
        // Одна строка про приватность — и она про почту устройств, а не про модели: за чужого
        // провайдера Point не обещает, тот разговор живёт на экране согласия.
        Text(
            SIGN_IN_PRIVACY,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = PortalColumnWidth),
        )

        if (state is SignIn.Waiting) {
            Spacer(Modifier.height(10.dp))
            TextButton(onClick = onCancel) {
                Text("Отменить", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** «Основное действие» экрана — та же светящаяся строка, что главное действие объекта. */
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

/** Знак аккаунта из общего словаря — им же подписан круг устройств. */
internal const val ACCOUNT_ICON = "account"

@Preview(name = "Вход · не вошёл (#472)", showBackground = true, backgroundColor = 0xFF0B0D10)
@Composable
private fun PreviewSignedOut() = PointTheme(darkTheme = true) {
    // Первое, что человек видит: одна строка смысла, одна кнопка, одна строка про приватность.
    SignInScreen(state = SignIn.SignedOut, onSignIn = {}, onCancel = {})
}

@Preview(name = "Вход · ждём браузер (#472)", showBackground = true, backgroundColor = 0xFF0B0D10)
@Composable
private fun PreviewWaiting() = PointTheme(darkTheme = true) {
    // Ожидание с кодом и выходом. Крутящегося колеса без двери здесь нет намеренно.
    SignInScreen(
        state = SignIn.Waiting(loginId = "l1", code = "K7-42Q", url = "https://point.leerio.app/login?d=l1"),
        onSignIn = {},
        onCancel = {},
    )
}

@Preview(name = "Вход · вошёл (#472)", showBackground = true, backgroundColor = 0xFF0B0D10)
@Composable
private fun PreviewSignedIn() = PointTheme(darkTheme = true) {
    // Удачу человек должен УВИДЕТЬ, а не догадаться о ней по исчезновению экрана.
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
    // Отказ говорит, ЧТО не так и ЧТО с этим делать, и дверь остаётся открытой.
    SignInScreen(
        state = SignIn.Refused(
            what = "До сервера Point не дозвониться",
            fix = "Проверьте интернет и попробуйте ещё раз — ничего не потеряно.",
        ),
        onSignIn = {},
        onCancel = {},
    )
}
