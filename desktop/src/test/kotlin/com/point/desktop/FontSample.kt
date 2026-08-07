package com.point.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.FontHinting
import androidx.compose.ui.text.FontRasterizationSettings
import androidx.compose.ui.text.FontSmoothing
import androidx.compose.ui.text.PlatformParagraphStyle
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.point.desktop.ui.PointColors
import com.point.desktop.ui.PointDesktopTheme
import com.point.desktop.ui.PointType

@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private val VARIANTS = listOf(
    "1 · как сейчас (умолчание библиотеки)" to null,
    "2 · субпиксельное + лёгкий хинтинг" to
        FontRasterizationSettings(FontSmoothing.SubpixelAntiAlias, FontHinting.Slight, true, false),
    "3 · субпиксельное + полный хинтинг" to
        FontRasterizationSettings(FontSmoothing.SubpixelAntiAlias, FontHinting.Full, false, true),
    "4 · обычное сглаживание + полный хинтинг" to
        FontRasterizationSettings(FontSmoothing.AntiAlias, FontHinting.Full, false, true),
    "5 · без сглаживания" to
        FontRasterizationSettings(FontSmoothing.None, FontHinting.Full, false, false),
)

@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "Point · образец шрифтов",
        state = androidx.compose.ui.window.rememberWindowState(width = 1100.dp, height = 780.dp),
    ) {
        PointDesktopTheme {
            Column(
                Modifier.fillMaxSize().background(PointColors.window).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                VARIANTS.forEach { (name, settings) ->
                    Sample(name, settings)
                }
            }
        }
    }
}

@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
@Composable
private fun Sample(name: String, settings: FontRasterizationSettings?) {
    val platform = settings?.let { PlatformTextStyle(null, PlatformParagraphStyle(it)) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(name, style = PointType.small)
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Text(
                "Point ждёт объект",
                style = PointType.display.copy(platformStyle = platform),
                modifier = Modifier.width(340.dp),
            )
            Text(
                "Счёт 4512 от ООО Ромашка · оплатить до 20 сентября",
                style = PointType.body.copy(platformStyle = platform),
            )
        }
    }
}
