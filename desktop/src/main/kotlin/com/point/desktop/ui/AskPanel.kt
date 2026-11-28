package com.point.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.point.core.model.tr
import com.point.desktop.DesktopState

/**
 * Вопрос действия — у самого объекта, там, где человек его нажал (ADR-0001 §18, #1014).
 *
 * Общий для всех действий компьютера: какое действие спрашивает, сам вопрос, готовые ответы,
 * если действие их предложило, и одна строка для своего. Слова кнопок — те же, что у вопроса
 * на телефоне: «Готово» запускает действие с ответом, «Отмена» снимает вопрос. Enter в поле —
 * то же «Готово»; пустой ответ ничего не запускает.
 */
@Composable
internal fun AskPanel(ask: DesktopState.InputAsk, onAnswer: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember(ask) { mutableStateOf("") }
    val focus = remember { FocusRequester() }

    // Курсор сразу в поле: человек только что нажал действие и ждёт, что можно печатать.
    LaunchedEffect(ask) { runCatching { focus.requestFocus() } }
    val submit = { if (text.isNotBlank()) onAnswer(text) }
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier.fillMaxWidth().glassPanel().padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(ask.title, style = PointType.note, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(ask.prompt, style = PointType.rowTitle)
        if (ask.suggestions.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ask.suggestions.forEach { suggestion -> PillButton(suggestion, { onAnswer(suggestion) }, small = true) }
            }
        }
        BasicTextField(
            value = text,
            onValueChange = { text = it },
            singleLine = true,
            textStyle = PointType.body,
            cursorBrush = SolidColor(PointColors.violet),
            modifier = Modifier.fillMaxWidth()
                .focusRequester(focus)
                .onPreviewKeyEvent { event ->
                    val enter = event.key == Key.Enter || event.key == Key.NumPadEnter
                    if (enter && event.type == KeyEventType.KeyDown) submit()
                    enter
                }
                .clip(shape)
                .background(Color.White.copy(alpha = 0.06f))
                .border(1.dp, Color.White.copy(alpha = 0.1f), shape)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        )
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PillButton(tr("Отмена"), onDismiss)
            LightButton(tr("Готово"), submit)
        }
    }
}
