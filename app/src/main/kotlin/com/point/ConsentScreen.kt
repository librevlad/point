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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.point.core.ui.PortalColumnWidth
import com.point.core.ui.PortalPlate
import com.point.core.ui.PortalRow
import com.point.core.ui.ScreenHeader
import com.point.core.ui.bubbleIcon
import com.point.core.ui.theme.PointTheme

@Composable
fun ConsentScreen(
    onAllow: () -> Unit,
    onDecline: () -> Unit,

    destination: String = "",
    title: String = "",
    confirm: String = "",

    /**
     * О чём спрашивают: что делаем и с чем (#1269).
     *
     * Экран показывается вместо объекта, а по стуку компьютера человек приходит сюда вовсе
     * с другого экрана — и без этой строки решает судьбу вещи, которой не видит.
     */
    about: String = "",
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

        if (about.isNotBlank()) {
            Text(
                about,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = PortalColumnWidth),
            )
            Spacer(Modifier.height(18.dp))
        }
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

            subtitle = destination.ifBlank { "Объект уйдёт на сервер AI-провайдера и вернётся результатом." },
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.widthIn(max = PortalColumnWidth),
        )
        Spacer(Modifier.height(24.dp))
        Column(modifier = Modifier.widthIn(max = PortalColumnWidth).fillMaxWidth()) {

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

private const val CLOUD_ICON = "cloud"

@Preview(name = "Облако · согласие моделям (#461)", showBackground = true, backgroundColor = 0xFF0B0D10)
@Composable
private fun PreviewConsent() = PointTheme(darkTheme = true) {
    ConsentScreen(onAllow = {}, onDecline = {})
}

@Preview(name = "Облако · просьба компьютера (#1269)", showBackground = true, backgroundColor = 0xFF0B0D10)
@Composable
private fun PreviewConsentForPc() = PointTheme(darkTheme = true) {

    ConsentScreen(
        onAllow = {},
        onDecline = {},
        about = "«Убрать фон» для компьютера · накладная.jpg",
    )
}

@Preview(name = "Облако · выложить по ссылке (#461)", showBackground = true, backgroundColor = 0xFF0B0D10)
@Composable
private fun PreviewConsentPublish() = PointTheme(darkTheme = true) {

    ConsentScreen(
        onAllow = {},
        onDecline = {},
        title = "Выложить файл по ссылке?",
        confirm = "Выложить",
        destination = "Файл уедет на сервер Point и сутки будет открыт любому, у кого есть ссылка.",
    )
}
