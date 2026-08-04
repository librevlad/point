package com.point

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.point.core.ui.PortalColumnWidth
import com.point.core.ui.PortalPlate
import com.point.core.ui.PortalRow
import com.point.core.ui.ScreenHeader
import com.point.core.ui.bubbleIcon
import com.point.core.ui.theme.PointTheme

/**
 * Cloud-privacy consent (#10). Cloud actions (AI, Перевести, В Excel) upload the
 * user's object to an AI provider — nothing leaves the device until the user agrees here.
 * True to Point's no-menu style: a single contextual decision, shown the moment it matters
 * (the first cloud action), not buried in settings.
 *
 * #114: вопрос называет своё обещание сам — [title] и [confirm] приходят от действия. «Выложить
 * файл по ссылке?» и «Отправить в облако?» — разные решения, и человек соглашается с тем, что
 * написано на кнопке, а не с «облаком вообще».
 *
 * Приведён к языку портала (#461). Экран говорил штатным Material поверх тёмного портала: заголовок
 * с `FontWeight.Bold` руками, фиолетовая таблетка `Button` — то есть самый важный вопрос приватности
 * задавался чужим голосом.
 *
 * Теперь у экрана есть герой: плита с облаком в кольце АКЦЕНТ2 — том самом кольце, которым на экране
 * объекта помечено действие, **уходящее с устройства**. Знак опознаётся раньше, чем прочтён текст.
 * Согласие — светящаяся строка основного действия, «Не сейчас» — тихая текстовая кнопка: ровно та
 * пара, что на экране ключа и на экране компьютера.
 */
@Composable
fun ConsentScreen(
    onAllow: () -> Unit,
    onDecline: () -> Unit,
    /** Куда именно уедет объект — текст зависит от действия (#388). */
    destination: String = "",
    title: String = "",
    confirm: String = "",
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Кольцо АКЦЕНТ2 — знак «это уходит с устройства», тот же, что носит AI-действие на экране
        // объекта. Здесь он на своём месте больше, чем где-либо: экран ровно про это и спрашивает.
        PortalPlate(
            accent = MaterialTheme.colorScheme.tertiary,
            icon = bubbleIcon(CLOUD_ICON),
            ring = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.9f),
            size = 76.dp,
            shape = RoundedCornerShape(24.dp),
        )
        Spacer(Modifier.height(18.dp))
        ScreenHeader(
            title = title.ifBlank { "Отправить в облако?" },
            subtitle = destination.ifBlank {
                "Объект уйдёт на сервер AI-провайдера и вернётся результатом. Ничего не " +
                    "отправляется без вашего согласия."
            },
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.widthIn(max = PortalColumnWidth),
        )
        Spacer(Modifier.height(24.dp))
        Column(modifier = Modifier.widthIn(max = PortalColumnWidth).fillMaxWidth()) {
            // «Основное действие» экрана — та же светящаяся строка, что главное действие объекта.
            // Слово на ней приносит действие: человек соглашается с тем, что написано.
            PortalRow(
                title = confirm.ifBlank { "Разрешить" },
                onClick = onAllow,
                icon = bubbleIcon(CLOUD_ICON),
                primary = true,
                chevron = false,
            )
        }
        Spacer(Modifier.height(10.dp))
        TextButton(onClick = onDecline) {
            Text("Не сейчас", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Знак «уходит с устройства» из общего словаря — им же подписана сама отправка. */
private const val CLOUD_ICON = "cloud"

@Preview(name = "Облако · согласие моделям (#461)", showBackground = true, backgroundColor = 0xFF0B0D10)
@Composable
private fun PreviewConsent() = PointTheme(darkTheme = true) {
    ConsentScreen(onAllow = {}, onDecline = {})
}

@Preview(name = "Облако · выложить по ссылке (#461)", showBackground = true, backgroundColor = 0xFF0B0D10)
@Composable
private fun PreviewConsentPublish() = PointTheme(darkTheme = true) {
    // #114 + #388: вопрос называет своё обещание, а подпись — куда именно уедет объект. Это другое
    // решение, чем «показать модели», и выглядеть оно обязано другими словами на той же строке.
    ConsentScreen(
        onAllow = {},
        onDecline = {},
        title = "Выложить файл по ссылке?",
        confirm = "Выложить",
        destination = "Файл ляжет на сервер Point и будет доступен любому, у кого есть ссылка, сутки.",
    )
}
