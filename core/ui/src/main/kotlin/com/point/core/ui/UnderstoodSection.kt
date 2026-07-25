package com.point.core.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.point.core.flow.META_ENTITY_PREFIX
import com.point.core.model.Feature
import com.point.core.model.PointObject

/**
 * «Point понял» (#114): one understood thing about the object — a label plus, when we
 * have it, the actual value («Нашёл телефон» · «+380 67…»). Never a raw feature name.
 */
data class UnderstoodFact(val key: String, val label: String, val value: String? = null)

/**
 * Derives the understanding checklist purely from the object: entity features pair with
 * their `entity.*` metadata values (kept by the enrichers), content features get a plain
 * sentence. Stable order — new findings append without reshuffling what's shown.
 */
fun understoodFacts(obj: PointObject): List<UnderstoodFact> {
    val state = obj.state
    fun entity(key: String) = obj.metadata[META_ENTITY_PREFIX + key]
    return buildList {
        if (state.has(Feature.HAS_PHONE)) add(UnderstoodFact("phone", "Нашёл телефон", entity("phone")))
        if (state.has(Feature.HAS_EMAIL)) add(UnderstoodFact("email", "Нашёл почту", entity("email")))
        if (state.has(Feature.HAS_URL)) add(UnderstoodFact("url", "Нашёл ссылку", entity("url")?.readableUrl()))
        if (state.has(Feature.HAS_ADDRESS)) add(UnderstoodFact("address", "Нашёл адрес", entity("address")))
        if (state.has(Feature.HAS_DATE)) add(UnderstoodFact("date", "Нашёл дату", entity("date")))
        if (state.has(Feature.HAS_CARD)) add(UnderstoodFact("card", "Нашёл карту", entity("card")?.maskedCard()))
        if (state.has(Feature.HAS_QR)) add(UnderstoodFact("qr", "Есть QR-код", entity("qr")?.readableUrl()))
        if (state.has(Feature.HAS_VCARD)) add(UnderstoodFact("vcard", "Это визитка"))
        if (state.has(Feature.IS_IMAGE_PDF)) add(UnderstoodFact("scan", "Это скан — текст не выделяется"))
        if (state.has(Feature.ZIP_OF_IMAGES)) add(UnderstoodFact("zip-images", "Архив из фотографий"))
    }
}

private fun String.readableUrl() =
    removePrefix("https://").removePrefix("http://").removePrefix("www.").trimEnd('/')

/** A card number is sensitive — the checklist shows only its tail («•• 5678»). */
private fun String.maskedCard() = "•• " + filter(Char::isDigit).takeLast(4)

/**
 * The understanding card — Point thinking out loud. Facts appear line by line as
 * enrichment lands (#64 delivers them progressively); still-running work shows as a
 * spinner line inside the same card, so "думает" and "понял" read as one monologue.
 */
@Composable
internal fun UnderstoodSection(facts: List<UnderstoodFact>, enriching: List<String>) {
    if (facts.isEmpty() && enriching.isEmpty()) return
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .padding(top = 16.dp)
            .widthIn(max = 340.dp)
            .animateContentSize(tween(220)),
    ) {
        Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp)) {
            facts.forEach { fact -> key(fact.key) { FactRow(fact) } }
            enriching.forEach { label ->
                key("running-$label") {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(vertical = 4.dp),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(11.dp),
                            strokeWidth = 1.5.dp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(9.dp))
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/** One understood line: an accent check, the claim, and the value it rests on. */
@Composable
private fun FactRow(fact: UnderstoodFact) {
    var appeared by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { appeared = true }
    val progress by animateFloatAsState(
        targetValue = if (appeared) 1f else 0f,
        animationSpec = tween(240),
        label = "fact-in",
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .padding(vertical = 4.dp)
            .graphicsLayer {
                alpha = progress
                translationY = (1f - progress) * 8.dp.toPx()
            },
    ) {
        Text(
            text = "✓",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(9.dp))
        Text(
            text = fact.label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        fact.value?.let { value ->
            Spacer(Modifier.width(6.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
