package com.point.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.point.core.ui.PointGlyph

/**
 * Справа пусто, пока объект не выбран: что сюда встанет. Как его принести, сказано слева, под
 * «Новым объектом», — второй раз здесь это был бы второй вход в то же действие. Поэтому здесь
 * не кнопки, а подсказки клавишами: их не видно нигде, кроме меню, а нажимают их отсюда же.
 *
 * Прежде здесь стояли знак и жирная строка посреди пустоты — владелец 21.10.2026: «смотрится
 * топорно». Теперь знак светится порталом, как в иконке, заголовок спокойнее, а под ним
 * сказано, зачем объект открывать.
 */
@Composable
internal fun NothingOpen(empty: Boolean) {
    Column(
        Modifier.fillMaxSize().padding(40.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(140.dp).drawBehind {
                drawCircle(
                    brush = Brush.radialGradient(
                        0f to PointColors.violet.copy(alpha = 0.22f),
                        0.55f to PointColors.blue.copy(alpha = 0.07f),
                        1f to Color.Transparent,
                    ),
                )
            },
            contentAlignment = Alignment.Center,
        ) {
            PointGlyph(size = 56.dp)
        }
        Text(
            if (empty) "Здесь откроется объект" else "Выберите объект слева",
            style = PointType.title.copy(fontWeight = FontWeight.Medium, fontSize = 17.sp),
        )
        Text(
            "Point покажет, что в нём, и подскажет, что с ним сделать",
            style = PointType.small,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp).widthIn(max = 320.dp),
        )
        Row(
            Modifier.padding(top = 26.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            KeyHint("Ctrl", "V", "из буфера")
            KeyHint("Ctrl", "O", "файл")
            KeyHint("Ctrl", "Shift", "S", "снимок")
        }
    }
}

/** Подсказка клавишами: клавиши колпачками, за ними — что они принесут. */
@Composable
private fun KeyHint(vararg parts: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        parts.dropLast(1).forEach { key ->
            Text(
                key,
                style = PointType.note.copy(color = PointColors.soft, fontWeight = FontWeight.Medium),
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.White.copy(alpha = 0.06f))
                    .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 6.dp, vertical = 1.dp),
            )
        }
        Text(parts.last(), style = PointType.note, modifier = Modifier.padding(start = 2.dp))
    }
}
