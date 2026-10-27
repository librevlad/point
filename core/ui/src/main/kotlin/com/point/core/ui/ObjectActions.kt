package com.point.core.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.point.core.flow.yieldLabel
import com.point.core.model.Bubble
import com.point.core.model.BubbleTier
import com.point.core.model.tr
import kotlinx.coroutines.delay
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import com.point.core.ui.theme.PointText

/**
 * Разделы действий — каждый со своим заголовком: у раздела значения это само значение
 * ([ValueHead]), у остальных — подпись раздела.
 *
 * [shared] — причина, общая для всех действий экрана: она уже сказана подписью объекта, и у
 * действий остаётся их обещание (#874). Считается по всем разделам сразу, даже когда экран
 * рисует их в двух местах.
 */
@Composable
internal fun ObjectActions(
    sections: List<ActionSection<Bubble>>,
    shared: String?,
    working: Boolean,
    onBubble: (Bubble) -> Unit,
    appIconFor: (String) -> ImageBitmap? = { null },
    head: (ActionSection<Bubble>) -> ValueHead? = { null },

    /** Театр экрана (#209): заголовок значения — место, куда оно приземляется. */
    theatre: TheatreState? = null,
) {
    val dim by animateFloatAsState(if (working) 0.5f else 1f, tween(MotionTokens.ACTIONS_DIM_MS), label = "actions-dim")
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer { alpha = dim },
    ) {
        sections.forEach { section ->
            key(section.key) {
                var expanded by rememberSaveable(section.key, section.items.size) { mutableStateOf(false) }
                val shown = if (expanded) section.items else section.items.take(shownAtOnce(section))
                val value = head(section)
                if (value != null) ValueHeader(value, theatre, section.key) else GroupHeader(section.title)

                // Раздел — одна панель стекла, строки в ней через тонкую линию (макет
                // 19.10.2026): список читается «заголовок → однородные строки», а не стопкой
                // отдельных карточек.
                GlassGroup {
                    shown.forEachIndexed { index, bubble ->
                        key(bubble.capabilityId.value) {
                            if (index > 0) GroupSeam()
                            ActionRow(
                                bubble = bubble,
                                shared = shared,
                                index = index,

                                // Подсвечено только продолжение найденного: у вещи без находок
                                // главного действия нет, есть честный выбор.
                                primary = section.leads && index == 0,
                                enabled = !working,
                                appIconFor = appIconFor,
                                onClick = { onBubble(bubble) },
                            )
                        }
                    }
                }
                val hidden = section.items.size - shown.size
                if (hidden > 0 || expanded) {
                    Text(
                        text = if (expanded) tr("Свернуть") else tr("Показать ещё {0}", hidden),
                        style = PointText.note,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable(enabled = !working, role = Role.Button) { expanded = !expanded }
                            .padding(vertical = 8.dp),
                    )
                }
            }
        }
    }
}

/**
 * Заголовок раздела значения: как значение называется, само значение и то, что про него
 * нужно знать до действия.
 *
 * Значение берётся одним касанием, как в строке знания (#650/#652/#693), — в буфер идёт
 * [copy], ровно то, что человек хотел бы вставить. Галочка — знак «Point это знает»:
 * значение неизвестно откуда её не получает (#948). Спор прочтений виден здесь же, где
 * человек решает позвонить (P8), — иначе он набрал бы номер, в котором Point сам сомневается.
 */
internal data class ValueHead(
    val name: String,
    val value: String?,
    val copy: String?,
    val known: Boolean,
    val other: String?,
)

@Composable
private fun ValueHeader(head: ValueHead, theatre: TheatreState? = null, key: String = "") {
    val clipboard = LocalClipboardManager.current

    // Приземлившееся значение коротко подсвечивает свою строку и гаснет; пока оно в воздухе,
    // строка держит его место и текст — для скринридера и пальца оно уже здесь.
    val lit = theatre?.lit(key) == true
    val glow = remember { Animatable(0f) }
    LaunchedEffect(lit, theatre?.landings) {
        if (lit) {
            glow.snapTo(1f)
            glow.animateTo(0f, tween(MotionTokens.LANDING_MS))
        }
    }
    val hidden = theatre?.hidden(key) == true
    var copied by rememberSaveable(head.name, head.copy) { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(FACT_COPIED_SHOWN_MS)
            copied = false
        }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 10.dp, end = 10.dp, top = 12.dp, bottom = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(PointPalette.violet.copy(alpha = 0.16f * glow.value))
            .let { base ->
                val copy = head.copy ?: return@let base
                base.clickable {
                    clipboard.setText(AnnotatedString(copy))
                    copied = true
                }
            }
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        SectionLabel(head.name)
        head.value?.let { value ->
            // Пока значение в воздухе, прячется вся строка: одинокая галочка без значения
            // читалась как пустой ответ (#209).
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.graphicsLayer { alpha = if (hidden) 0f else 1f },
            ) {
                Text(
                    text = if (head.known) "✓" else "·",
                    style = PointText.rowTitle.copy(fontSize = PointText.rowTitle.fontSize * 1.1f),
                    fontWeight = FontWeight.Bold,
                    color = if (head.known) PointPalette.violet else PointPalette.muted,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (copied) tr("Скопировано") else value,
                    style = PointText.rowTitle.copy(fontSize = PointText.rowTitle.fontSize * 1.1f),
                    color = if (copied) PointPalette.violet else PointPalette.text,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.theatreTarget(theatre, key),
                )
            }
        }
        head.other?.let { other ->
            Text(
                text = tr("или: {0}", other),
                style = PointText.note,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ActionRow(
    bubble: Bubble,
    shared: String?,
    index: Int,
    primary: Boolean,
    enabled: Boolean,
    appIconFor: (String) -> ImageBitmap?,
    onClick: () -> Unit,
) {
    val isApp = bubble.icon.startsWith("app:")
    val appIcon = if (isApp) remember(bubble.icon) { appIconFor(bubble.icon.removePrefix("app:")) } else null
    val ai = bubble.tier == BubbleTier.AI
    PortalRow(
        title = bubble.title,
        subtitle = yieldLabel(bubble.yields, bubble.unusableReason.takeIf { it != shared }),
        subtitleMaxLines = 1,
        onClick = onClick,
        icon = bubbleIcon(bubble.icon),
        image = appIcon,
        accent = if (isApp) PointPalette.violet else bubbleColor(bubble.icon),

        // Действие с AI отмечено кромкой плашки: оно читает вещь моделью, и это видно до тапа.
        ring = if (ai) PointPalette.cyan.copy(alpha = 0.7f) else null,
        primary = primary,
        enabled = enabled,
        surface = false,
        appearIndex = index,
    )
}
