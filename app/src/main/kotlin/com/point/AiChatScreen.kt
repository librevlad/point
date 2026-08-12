package com.point

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.point.core.model.ChatMessage
import com.point.core.model.ChatRole
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import com.point.core.ui.Portal
import com.point.core.ui.PortalColumnWidth
import com.point.core.ui.PortalPlate
import com.point.core.ui.PortalPlateShape
import com.point.core.ui.PortalRow
import com.point.core.ui.SectionLabel
import com.point.core.ui.ThinkingDot
import com.point.core.ui.bubbleColor
import com.point.core.ui.bubbleIcon
import com.point.core.ui.kindLabel
import com.point.core.ui.portalCard
import com.point.core.ui.portalPrimary
import com.point.core.ui.theme.PointTheme

@Composable
fun AiChatScreen(
    chat: ChatState,
    onSend: (String) -> Unit,
    onClose: () -> Unit,

    onCancel: () -> Unit = {},

    onTakeAnswer: () -> Unit = {},

    onRunOffer: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize().imePadding()) {

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IconButton(onClick = onClose) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Назад",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box(Modifier.size(38.dp), contentAlignment = Alignment.Center) {
                Portal(size = 38.dp, intensity = 0.75f)
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = "Спросить AI",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = chat.obj.metadata["name"] ?: kindLabel(chat.obj.state.kind),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        val listState = rememberLazyListState()
        val count = chat.messages.size + if (chat.pending) 1 else 0
        LaunchedEffect(count) {
            if (count > 0) listState.animateScrollToItem(count - 1)
        }

        if (chat.messages.isEmpty() && !chat.pending) {
            ChatSuggestions(chat.suggestions, onSend = onSend, modifier = Modifier.weight(1f))
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                itemsIndexed(chat.messages) { _, m -> ChatBubble(m) }
                if (chat.pending) item { TypingBubble() }
            }
        }

        chat.notice?.let { notice ->
            Text(
                text = notice,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                textAlign = TextAlign.Center,
            )
        }

        // Разговор вещей не делает: узнанную просьбу он показывает действием (#804).
        chat.offer?.let { offer ->
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(Modifier.widthIn(max = PortalColumnWidth).fillMaxWidth()) {
                    PortalRow(
                        title = offer.title,
                        subtitle = OFFER_WHAT,
                        onClick = onRunOffer,
                        icon = bubbleIcon("pdf"),
                        accent = bubbleColor("pdf"),
                    )
                }
            }
        }

        if (takeableAnswer(chat) != null) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(Modifier.widthIn(max = PortalColumnWidth).fillMaxWidth()) {
                    PortalRow(
                        // Второй строки нет (#582): имя строки уже сказало, что произойдёт.
                        title = "Забрать ответ",
                        onClick = onTakeAnswer,
                        icon = bubbleIcon("text"),
                        accent = bubbleColor("text"),
                    )
                }
            }
        }

        ChatInput(pending = chat.pending, onSend = onSend, onCancel = onCancel)
    }
}

/**
 * Забрать можно ответ, а не исход операции (#793, решение владельца 11.08.2026).
 *
 * Офлайн-прогон: вопрос не прошёл, в чате осталось «Не получилось ответить: Модель недоступна»
 * — и «Забрать ответ» рождало из этой фразы объект «Текст · Ответ AI», который дальше можно
 * было понять, перевести и отправить на компьютер. Неудача — свойство операции и знанием об
 * объекте не становится.
 */
fun takeableAnswer(chat: ChatState): String? {
    if (chat.pending) return null
    val last = chat.messages.lastOrNull { it.role == ChatRole.ASSISTANT } ?: return null
    if (last.failed) return null
    return last.text.takeIf { it.isNotBlank() }
}

@Composable
private fun ChatSuggestions(suggestions: List<String>, onSend: (String) -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(11.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier.widthIn(max = PortalColumnWidth).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            SectionLabel("О чём спросить")
            suggestions.forEachIndexed { index, s ->
                PortalRow(
                    title = s,
                    onClick = { onSend(s) },
                    icon = bubbleIcon(AI_ICON),
                    accent = bubbleColor(AI_ICON),
                    ring = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.9f),
                    appearIndex = index,
                )
            }
        }
    }
}

private const val AI_ICON = "ai"

private val BubbleMaxWidth = 300.dp

@Composable
private fun ChatBubble(message: ChatMessage) {
    val user = message.role == ChatRole.USER
    val shape = RoundedCornerShape(if (user) 18.dp else 16.dp)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (user) Arrangement.End else Arrangement.Start,
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = BubbleMaxWidth)

                .then(if (user) Modifier.portalPrimary(shape, elevation = 12.dp) else Modifier.portalCard(shape))
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            SelectionContainer {
                Text(
                    text = message.text,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (user) Color.White else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun TypingBubble() {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Row(
            modifier = Modifier
                .portalCard(RoundedCornerShape(16.dp))
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            ThinkingDot()
            Text(
                text = "Думаю…",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ChatInput(pending: Boolean, onSend: (String) -> Unit, onCancel: () -> Unit) {

    var text by rememberSaveable { mutableStateOf("") }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.weight(1f),
            placeholder = { Text("Задайте вопрос…") },
            maxLines = 4,
        )
        val canSend = !pending && text.isNotBlank()
        val active = pending || canSend

        Box(
            modifier = Modifier
                .clip(PortalPlateShape)
                .clickable(enabled = active) {
                    if (pending) onCancel() else { onSend(text); text = "" }
                }
                .semantics { contentDescription = if (pending) "Остановить" else "Отправить" },
        ) {
            PortalPlate(
                accent = MaterialTheme.colorScheme.primary,
                icon = if (pending) Icons.Filled.Close else Icons.AutoMirrored.Filled.Send,
                onGlass = false,
                modifier = Modifier.graphicsLayer { alpha = if (active) 1f else 0.45f },
            )
        }
    }
}

private fun previewObject() = PointObject(
    id = "preview",
    mime = "application/pdf",
    uri = ScratchRef("/preview/договор.pdf"),
    state = ObjectState(ObjectKind.PDF),
    metadata = mapOf("name" to "договор.pdf"),
)

@Preview(name = "Чат · о чём спросить (#461)", showBackground = true, backgroundColor = 0xFF0B0D10)
@Composable
private fun PreviewChatEmpty() = PointTheme(darkTheme = true) {

    AiChatScreen(
        chat = ChatState(
            obj = previewObject(),
            suggestions = listOf(
                "О чём этот документ?",
                "Какие сроки и суммы в нём названы?",
                "Что здесь стоит проверить внимательно?",
            ),
        ),
        onSend = {},
        onClose = {},
    )
}

@Preview(name = "Чат · разговор кончается объектом (#491)", showBackground = true, backgroundColor = 0xFF0B0D10)
@Composable
private fun PreviewChatTakeAnswer() = PointTheme(darkTheme = true) {

    AiChatScreen(
        chat = ChatState(
            obj = previewObject(),
            messages = listOf(
                ChatMessage(ChatRole.USER, "Собери из договора сроки и суммы списком"),
                ChatMessage(
                    ChatRole.ASSISTANT,
                    "Срок аренды — 11 месяцев с 01.09.\nПлатёж — 42 000 ₽ до 5-го числа.\n" +
                        "Залог — 42 000 ₽, возвращается в течение 10 дней после выезда.",
                ),
            ),
        ),
        onSend = {},
        onClose = {},
    )
}

@Preview(name = "Чат · разговор и ожидание (#461)", showBackground = true, backgroundColor = 0xFF0B0D10)
@Composable
private fun PreviewChatThread() = PointTheme(darkTheme = true) {

    AiChatScreen(
        chat = ChatState(
            obj = previewObject(),
            messages = listOf(
                ChatMessage(ChatRole.USER, "О чём этот документ?"),
                ChatMessage(
                    ChatRole.ASSISTANT,
                    "Это договор аренды помещения на 11 месяцев с автоматическим продлением. " +
                        "Ключевые суммы — платёж и залог — названы в пункте 4.",
                ),
                ChatMessage(ChatRole.USER, "А когда его надо продлить?"),
            ),
            pending = true,
        ),
        onSend = {},
        onClose = {},
    )
}

/** Разговор не делает вещей сам: он называет действие, которое их делает (#804). */
private const val OFFER_WHAT = "Это делает действие объекта — тапните, и оно выполнится"
