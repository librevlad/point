package com.point.core.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

/**
 * Исход только что сделанного — карточка под объектом, в языке портала (`docs/design-system.png`).
 *
 * Пока исход стоял последним элементом прокрутки, его никто не видел (#358), и то, каким он
 * нарисован, значения не имело. Поднятый под объект, он оказался единственным на экране блоком
 * стандартного Material: тревожный красный прямоугольник посреди тёмного портала — чужой голос,
 * которым Point не говорит нигде больше.
 *
 * Теперь карточка носит ровно те же токены, что строка действия и карточка понятого: тёмная
 * поверхность с верхней подсветкой, скругление 18, слева — светящийся знак. Различают исходы
 * **знак и его свет**, а не громкость: «✓» в фиолетовом АКЦЕНТ1 (свет самого портала) против «✕»
 * в тёплом конце фирменного градиента. Цвет здесь — второе сообщение после текста: удача не имеет
 * права выглядеть сбоем, а отказ обязан отличаться — но не обязан орать.
 */

/**
 * Тёплый конец градиента дизайн-системы (`docs/design-system.png`, полоса «ГРАДИЕНТЫ»: фиолетовый
 * → синий → коралл; замер по картинке — `#F85938`).
 *
 * Отказ светится им, а не красным Material `errorContainer` `#93000A`: тревога остаётся в палитре
 * портала, где у неё уже есть законное место, вместо цвета из чужой системы.
 */
private val OutcomeWarm = Color(0xFFF85938)

@Composable
internal fun OutcomeBanner(message: String?, failure: Boolean) {
    // Последнее сказанное держится, пока карточка уезжает, — и держится ВМЕСТЕ со своим исходом:
    // иначе отказ на выезде перекрашивался бы в удачу, потому что флаг ушёл раньше текста.
    var shown by remember { mutableStateOf("") }
    var shownFailure by remember { mutableStateOf(false) }
    LaunchedEffect(message, failure) {
        if (message != null) {
            shown = message
            shownFailure = failure
        }
    }

    AnimatedVisibility(
        visible = message != null,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
    ) {
        val accent = if (shownFailure) OutcomeWarm else MaterialTheme.colorScheme.primary
        val shape = RoundedCornerShape(18.dp)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .padding(top = 16.dp)
                // Той же ширины, что карточка понятого и карточка готовности, — исход стоит с ними
                // одной колонкой, а не выпадает из неё отдельной плашкой.
                .widthIn(max = 340.dp)
                .shadow(6.dp, shape, ambientColor = Color.Black, spotColor = Color.Black)
                .clip(shape)
                .background(Brush.verticalGradient(listOf(RowTop, RowBottom)))
                // Свет исхода ложится с той стороны, где стоит знак, и гаснет к тексту: карточка
                // окрашена, но читается как поверхность портала, а не как цветной блок.
                .background(Brush.horizontalGradient(listOf(accent.copy(alpha = 0.16f), Color.Transparent)))
                .border(1.dp, Brush.verticalGradient(listOf(TopHighlight, Color.Transparent)), shape)
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            // Знак зажигается заново на каждый новый исход, а не один раз за жизнь карточки:
            // второе подряд действие иначе досталось бы уже погасшему знаку.
            key(shown) { OutcomeMark(accent = accent, failure = shownFailure) }
            Text(
                text = shown,
                style = MaterialTheme.typography.bodyMedium,
                // Ответ Point — главный текст этой карточки, поэтому он белый (ТЕКСТ), а не
                // приглушённый: приглушённым он выглядел подписью к чему-то важнее себя.
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/**
 * Знак исхода — кружок в языке иконных плиток списка действий: плита, радиальное свечение своего
 * цвета, тонкое кольцо. Загорается при появлении и оседает (MOTION.md: импульс, а не крутилка);
 * при выключенной анимации просто стоит на месте.
 *
 * Голосом экрана он тоже назван («Готово» / «Не получилось»): исход не должен держаться на одном
 * цвете — ни для человека, который цвета не различает, ни для того, кто слушает экран.
 */
@Composable
private fun OutcomeMark(accent: Color, failure: Boolean) {
    val motion = rememberMotionEnabled()
    var appeared by remember { mutableStateOf(!motion) }
    LaunchedEffect(Unit) { appeared = true }
    val ignite by animateFloatAsState(
        targetValue = if (appeared) 0f else 1f,
        animationSpec = tween(520),
        label = "outcome-ignite",
    )
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(34.dp)
            .graphicsLayer {
                val s = 1f + 0.22f * ignite
                scaleX = s
                scaleY = s
            }
            .clip(CircleShape)
            .background(PlateBase)
            .background(
                Brush.radialGradient(
                    listOf(accent.copy(alpha = (0.34f + 0.34f * ignite).coerceAtMost(1f)), Color.Transparent),
                ),
            )
            .border(1.dp, accent.copy(alpha = 0.45f), CircleShape),
    ) {
        Icon(
            imageVector = if (failure) Icons.Filled.Close else Icons.Filled.Check,
            contentDescription = if (failure) "Не получилось" else "Готово",
            tint = accent,
            modifier = Modifier.size(18.dp),
        )
    }
}
