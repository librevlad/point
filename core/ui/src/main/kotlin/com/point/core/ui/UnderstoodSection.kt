package com.point.core.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import kotlinx.coroutines.delay
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.point.core.flow.META_ENTITY_PREFIX
import com.point.core.flow.META_SEMANTIC_SUMMARY
import com.point.core.flow.META_SEMANTIC_TYPE
import com.point.core.flow.META_SIZE
import com.point.core.flow.unusableReasonOf
import com.point.core.flow.documentLabel
import com.point.core.flow.humanWeight
import com.point.core.flow.maskedForScreen
import com.point.core.flow.provenanceLabel
import com.point.core.flow.provenanceOf
import com.point.core.flow.recordingLength
import com.point.core.model.Feature
import com.point.core.model.ObjectKind
import com.point.core.model.PointObject

data class UnderstoodFact(
    val key: String,
    val label: String,
    val value: String? = null,
    val note: String? = null,
)

fun understoodFacts(obj: PointObject): List<UnderstoodFact> {
    val state = obj.state
    fun entity(key: String) = obj.metadata[META_ENTITY_PREFIX + key]

    // Слово человека видно и здесь, не только на chip (ADR-0001 §14): машинные
    // происхождения строку фактов не подписывают — их место на самих значениях.
    fun note(key: String): String? =
        com.point.core.model.Provenance.HUMAN
            .takeIf { provenanceOf(obj.metadata, META_ENTITY_PREFIX + key) == it }
            ?.let { provenanceLabel(it) }
    val summary = obj.metadata[com.point.core.flow.META_SEMANTIC_SUMMARY]
    return buildList {

        if (state.has(Feature.IS_MEETING)) add(UnderstoodFact("semantic", "Это встреча", summary))
        if (state.has(Feature.IS_PURCHASE)) add(UnderstoodFact("semantic", "Это покупка", summary))
        if (state.has(Feature.IS_RECIPE)) add(UnderstoodFact("semantic", "Это рецепт", summary))
        if (state.has(Feature.IS_JOB)) add(UnderstoodFact("semantic", "Это вакансия", summary))
        if (state.has(Feature.HAS_PHONE)) add(UnderstoodFact("phone", "Нашёл телефон", entity("phone"), note("phone")))
        if (state.has(Feature.HAS_EMAIL)) add(UnderstoodFact("email", "Нашёл почту", entity("email"), note("email")))
        if (state.has(Feature.HAS_URL)) add(UnderstoodFact("url", "Нашёл ссылку", entity("url")?.readableUrl(), note("url")))
        if (state.has(Feature.HAS_ADDRESS)) add(UnderstoodFact("address", "Нашёл адрес", entity("address"), note("address")))
        if (state.has(Feature.HAS_DATE)) add(UnderstoodFact("date", "Нашёл дату", entity("date"), note("date")))
        if (state.has(Feature.HAS_CARD)) {
            add(
                UnderstoodFact(
                    "card",
                    "Нашёл карту",
                    entity("card")?.let { maskedForScreen(META_ENTITY_PREFIX + "card", it) },
                    note("card"),
                ),
            )
        }
        if (state.has(Feature.HAS_QR)) add(UnderstoodFact("qr", "Есть QR-код", entity("qr")?.readableUrl(), note("qr")))
        if (state.has(Feature.HAS_VCARD)) add(UnderstoodFact("vcard", "Это визитка"))
        if (state.has(Feature.IS_IMAGE_PDF)) add(UnderstoodFact("scan", "Это скан — текст не выделяется"))
        if (state.has(Feature.ZIP_OF_IMAGES)) add(UnderstoodFact("zip-images", "Архив из фотографий"))
    }
}

data class ObjectVerdict(val headline: String, val subline: String?, val measure: String? = null)

fun objectMeasure(obj: PointObject): String? {
    if (obj.state.kind != ObjectKind.AUDIO) return null
    val size = obj.metadata[META_SIZE]?.toLongOrNull() ?: return null
    return recordingLength(obj.mime, size, obj.metadata["name"]) ?: humanWeight(size)
}

fun objectVerdict(obj: PointObject): ObjectVerdict {
    val state = obj.state
    val headline = when {
        state.has(Feature.IS_PURCHASE) -> "Покупка"
        state.has(Feature.IS_MEETING) -> "Встреча"
        state.has(Feature.IS_RECIPE) -> "Рецепт"
        state.has(Feature.IS_JOB) -> "Вакансия"
        state.has(Feature.HAS_VCARD) -> "Визитка"

        else -> documentLabel(obj.metadata[META_SEMANTIC_TYPE]) ?: heroKindLabel(obj)
    }

    // Годность — часть состояния объекта (#684/#685): человек читает её здесь же, до
    // первого тапа, — она важнее названия файла или того, что успела сказать модель.
    val unusable = unusableReasonOf(obj.metadata).takeIf { state.has(Feature.UNUSABLE) }
    val summary = obj.metadata[META_SEMANTIC_SUMMARY]?.takeIf { it.isNotBlank() }
    val name = obj.metadata["name"]?.takeIf { it.isNotBlank() && it != headline }
    return ObjectVerdict(headline, unusable ?: summary ?: name, objectMeasure(obj))
}

private fun heroKindLabel(obj: PointObject): String =
    if (objectMark(obj) == ObjectMark.SPREADSHEET) "Таблица" else kindLabel(obj.state.kind)

private fun String.readableUrl() =
    removePrefix("https://").removePrefix("http://").removePrefix("www.").trimEnd('/')

/**
 * «Не удалось посмотреть» — не «не найдено»: причина от ридера уже человеческая
 * (Этап 4), объект жив, повтор возможен. Пусто — молчим.
 */
fun failedNote(failed: List<com.point.core.flow.FailedInvestigation>): String? {
    val reasons = failed.map { it.reason.trim() }.filter { it.isNotBlank() }.distinct()
    if (reasons.isEmpty()) return null
    return "Не удалось посмотреть: " + reasons.joinToString("; ")
}

@Composable
internal fun UnderstoodSection(
    facts: List<UnderstoodFact>,
    enriching: List<String>,
    failed: List<com.point.core.flow.FailedInvestigation> = emptyList(),
) {

    val detail = facts.filter { it.key != "semantic" }
    val trouble = failedNote(failed)
    if (detail.isEmpty() && enriching.isEmpty() && trouble == null) return
    Surface(
        shape = PortalCardShape,
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .padding(top = 16.dp)
            .widthIn(max = PortalColumnWidth)
            .animateContentSize(tween(220)),
    ) {
        Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp)) {
            detail.forEach { fact -> key(fact.key) { FactRow(fact) } }
            trouble?.let { note ->
                Text(
                    text = note,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }
            enriching.forEach { label ->
                key("running-$label") {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(vertical = 4.dp),
                    ) {

                        ThinkingDot()
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

@Composable
fun ThinkingDot() {
    val motion = rememberMotionEnabled()
    val alpha = if (motion) {
        rememberInfiniteTransition(label = "think").animateFloat(
            initialValue = 0.3f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
            label = "dot",
        ).value
    } else {
        1f
    }
    Box(
        Modifier
            .size(9.dp)
            .graphicsLayer { this.alpha = alpha }
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary),
    )
}

@Composable
private fun FactRow(fact: UnderstoodFact) {
    var appeared by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { appeared = true }
    val progress by animateFloatAsState(
        targetValue = if (appeared) 1f else 0f,
        animationSpec = tween(240),
        label = "fact-in",
    )
    val flash by animateFloatAsState(
        targetValue = if (appeared) 0f else 1f,
        animationSpec = tween(520),
        label = "fact-ignite",
    )
    val accent = MaterialTheme.colorScheme.primary

    // Любое показанное значение берётся одним касанием — «мне в буфере целиковые
    // блоки не нужны» (владелец, #650/#652/#693). В буфер идёт только value, без
    // подписи и без note: ровно то, что человек хотел скопировать.
    val clipboard = LocalClipboardManager.current
    var copied by rememberSaveable(fact.key) { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(FACT_COPIED_SHOWN_MS)
            copied = false
        }
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .graphicsLayer {
                alpha = progress
                translationY = (1f - progress) * 8.dp.toPx()
            }
            .clip(RoundedCornerShape(8.dp))
            .let {
                if (fact.value != null) {
                    it.clickable {
                        clipboard.setText(AnnotatedString(fact.value))
                        copied = true
                    }
                } else {
                    it
                }
            }
            .background(accent.copy(alpha = 0.16f * flash))
            .padding(horizontal = 6.dp, vertical = 4.dp),
    ) {
        Text(
            text = "✓",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.graphicsLayer {
                val s = 1f + 0.35f * flash
                scaleX = s
                scaleY = s
            },
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
                text = if (copied) "Скопировано" else value,
                style = MaterialTheme.typography.labelLarge,
                color = if (copied) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        fact.note?.let { note ->
            Spacer(Modifier.width(6.dp))
            Text(
                text = note,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
            )
        }
    }
}

private const val FACT_COPIED_SHOWN_MS = 1_600L
