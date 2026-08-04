package com.point.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import com.point.desktop.PcConfig

/**
 * Пустой экран десктопа (#285, мокап «3a Пусто · подключить телефон»).
 *
 * Конвейер ещё не начался — и экран занят ровно тем, чем его начать: портал ждёт объект, рядом
 * три способа его дать, справа — как подключить телефон. Прежний экран показывал только QR, то
 * есть отвечал на вопрос «как связать устройства» вместо «что здесь делать».
 */
@Composable
fun EmptyScreen(
    config: PcConfig,
    addresses: List<String>,
    port: Int,
    onTakeClipboard: () -> Unit = {},
    /** Круг устройств аккаунта (#473) — на месте, где стояла карточка с QR. */
    devices: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxSize().padding(horizontal = 56.dp),
        horizontalArrangement = Arrangement.spacedBy(72.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Колонка берёт доступную высоту и центрирует содержимое ВНУТРИ окна. Без этого она
        // мерилась по своему содержимому и на невысоком экране уезжала за нижний край — третий
        // способ начать работу молча пропадал.
        Column(
            modifier = Modifier.fillMaxHeight(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterVertically),
        ) {
            Portal()
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Point ждёт объект", style = PointType.display)
                Text(
                    "Дальше он покажет, что с ним можно сделать — и весь путь останется на экране",
                    style = PointType.small,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.width(320.dp),
                )
            }
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.width(360.dp),
            ) {
                WayIn("Перетащить файл в окно", PointColors.violet)
                WayIn(
                    "Взять то, что в буфере",
                    PointColors.cyan,
                    hotkey = "Ctrl+Shift+V",
                    onClick = onTakeClipboard,
                )
                WayIn("Поделиться с телефона в Point", PointColors.muted)
            }
        }

        // Связывать больше нечего: телефон, вошедший в тот же аккаунт, уже в круге. На месте QR
        // стоит то, что человеку теперь правда нужно видеть — что у него есть и на связи ли оно (#472).
        devices?.invoke()
    }
}

/**
 * Портал: два кольца, внешнее — фиолетовое, внутреннее — голубое.
 *
 * Тот же знак, что на телефоне: экран без объекта не пустует, он ждёт.
 */
@Composable
private fun Portal() {
    Box(Modifier.size(190.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(190.dp)
                .background(
                    Brush.radialGradient(
                        listOf(PointColors.violet.copy(alpha = 0.20f), Color.Transparent),
                    ),
                    CircleShape,
                )
                .border(2.dp, PointColors.violet.copy(alpha = 0.85f), CircleShape),
        )
        Box(Modifier.size(164.dp).border(1.dp, PointColors.cyan.copy(alpha = 0.35f), CircleShape))
    }
}

/** Способ дать объект: точка-акцент, название и — если есть — горячая клавиша. */
@Composable
private fun WayIn(title: String, dot: Color, hotkey: String? = null, onClick: (() -> Unit)? = null) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .background(Brush.verticalGradient(listOf(PointColors.surface, PointColors.surfaceDeep)))
            .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(14.dp))
            .padding(horizontal = 15.dp, vertical = 11.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).background(dot, CircleShape))
        Text(title, style = PointType.body, modifier = Modifier.weight(1f))
        if (hotkey != null) {
            Text(
                hotkey,
                style = PointType.mono,
                modifier = Modifier
                    .border(1.dp, PointColors.border, RoundedCornerShape(5.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}
