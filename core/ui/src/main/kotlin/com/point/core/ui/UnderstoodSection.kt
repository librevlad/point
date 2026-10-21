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
import com.point.core.model.tr
import kotlinx.coroutines.delay
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.point.core.flow.META_ENTITY_PREFIX
import com.point.core.flow.META_SEMANTIC_SUMMARY
import com.point.core.flow.maskedForScreen
import com.point.core.flow.provenanceLabel
import com.point.core.flow.provenanceOf
import com.point.core.model.Feature
import com.point.core.model.PointObject

data class UnderstoodFact(
    val key: String,
    val label: String,
    val value: String? = null,
    val note: String? = null,

    /**
     * Названо ли происхождение значения (#948). Галочка достаётся только известному:
     * прочитанному, выведенному, понятому, подтверждённому. Неизвестное показывается тише.
     */
    val known: Boolean = true,
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

    // Откуда значение — известно? Неизвестное не получает галочки (#948).
    fun known(key: String): Boolean =
        com.point.core.flow.isKnownFor(obj.metadata, META_ENTITY_PREFIX + key)
    val summary = obj.metadata[com.point.core.flow.META_SEMANTIC_SUMMARY]
    return buildList {

        if (state.has(Feature.IS_MEETING)) add(UnderstoodFact("semantic", tr("Это встреча"), summary))
        if (state.has(Feature.IS_PURCHASE)) add(UnderstoodFact("semantic", tr("Это покупка"), summary))
        if (state.has(Feature.IS_RECIPE)) add(UnderstoodFact("semantic", tr("Это рецепт"), summary))
        if (state.has(Feature.IS_JOB)) add(UnderstoodFact("semantic", tr("Это вакансия"), summary))
        // Строку номера собирает знание, а не экран (#932): рядом с номером — то, что про
        // него известно, вид и чужая страна словом. Своя страна не называется — человек
        // знает, где живёт.
        //
        // Разобранный вид, страна и вид есть только у номера, чью страну назвал документ
        // (#1029). Номер без кода страны — обычный случай кадра — показывается как прочитан,
        // покорёженный `06 1 ) 2 80-44-2 1` тоже: принятая цена, карточка #1294.
        if (state.has(Feature.HAS_PHONE)) {
            val phone = entity("phone")
                ?.let { com.point.core.flow.shownKnowledge(com.point.core.flow.META_ENTITY_PHONE, it, obj.metadata) }
            add(UnderstoodFact("phone", tr("Нашёл телефон"), phone, note("phone"), known("phone")))
        }
        if (state.has(Feature.HAS_EMAIL)) add(UnderstoodFact("email", tr("Нашёл почту"), entity("email"), note("email"), known("email")))
        if (state.has(Feature.HAS_URL)) add(UnderstoodFact("url", tr("Нашёл ссылку"), entity("url")?.readableUrl(), note("url"), known("url")))
        if (state.has(Feature.HAS_ADDRESS)) add(UnderstoodFact("address", tr("Нашёл адрес"), entity("address"), note("address"), known("address")))
        if (state.has(Feature.HAS_DATE)) add(UnderstoodFact("date", tr("Нашёл дату"), entity("date"), note("date"), known("date")))
        if (state.has(Feature.HAS_CARD)) {
            add(
                UnderstoodFact(
                    "card",
                    tr("Нашёл карту"),
                    entity("card")?.let { maskedForScreen(META_ENTITY_PREFIX + "card", it) },
                    note("card"),
                    known("card"),
                ),
            )
        }
        // Код на кадре — состояние объекта; что в нём написано — знание (#1119). Когда в
        // коде оказалась ссылка, она и есть знание, и повторять её здесь второй строкой
        // незачем: строка говорит только то, что код на кадре есть, и это Point видел сам.
        if (state.has(Feature.HAS_QR)) {
            val content = entity("qr")?.readableUrl()
            add(UnderstoodFact("qr", tr("Есть QR-код"), content, note("qr"), content?.let { known("qr") } ?: true))
        }

        // Цифры кода видны и копируются одним тапом (#445). Про сам товар Point молчит:
        // название, производитель и цена — чужая уверенность, а не наблюдение Point.
        if (state.has(Feature.HAS_BARCODE)) {
            add(UnderstoodFact("barcode", tr("Штрихкод"), entity("barcode"), note("barcode")))
        }

        // Снимок рассказывает о себе (#547). Координаты показываются и когда их нашла модель
        // в тексте, и когда они пришли из самого снимка — строка одна на оба источника.
        if (state.has(Feature.HAS_SHOT_AT)) {
            add(UnderstoodFact("shot-at", tr("Снято"), obj.metadata[com.point.core.flow.META_SHOT_AT]))
        }
        if (state.has(Feature.HAS_GEO) || obj.metadata[com.point.core.flow.META_ENTITY_GEO] != null) {
            add(UnderstoodFact("geo", tr("Место съёмки"), entity("geo"), note("geo")))
        }
        if (state.has(Feature.HAS_VCARD)) add(UnderstoodFact("vcard", tr("Это визитка")))
        // Признак отвечает на один вопрос: достаётся ли текст из самого файла (#933, #995).
        // «Текст не выделяется» было правдой только про пустой слой; у документа с подменённой
        // раскладкой шрифта слой есть и выделяется — мусором. Названо то, что Point проверил.
        if (state.has(Feature.IS_IMAGE_PDF)) add(UnderstoodFact("scan", tr("Текст из файла не достаётся")))
        if (state.has(Feature.ZIP_OF_IMAGES)) add(UnderstoodFact("zip-images", tr("Архив из фотографий")))

        // Всё остальное знание — тем же списком (#935).
        //
        // Строки выше — перечисление признаков, и оно всегда отставало от того, что Point
        // успел узнать. Сумма счёта лежала в графе разобранная («7 800 грн», прочитано с
        // текста) и на экран не попадала вовсе, потому что своей строки в перечислении у неё
        // не было. Компьютер тот же объект показывал целиком: у него список общий.
        //
        // Один объект — одно знание на обоих устройствах. Признаковые строки остаются: у них
        // человеческие формулировки и своя обработка значения (маска карты, читаемая ссылка).
        val shown = map { it.key }.toSet()
        com.point.core.flow.knowledgeRows(obj.metadata)
            .map { it.key.removePrefix(META_ENTITY_PREFIX) to it }
            .filterNot { (key, _) -> key in shown }
            .forEach { (key, row) ->
                add(UnderstoodFact(key, row.name, row.value, note(key), known(key)))
            }
    }
}

private fun String.readableUrl() =
    removePrefix("https://").removePrefix("http://").removePrefix("www.").trimEnd('/')

/**
 * «Не удалось посмотреть» — не «не найдено»: причина от ридера уже человеческая
 * (Этап 4), объект жив, повтор возможен. Пусто — молчим.
 *
 * Сами слова объявлены в `:core:flow` (#1272): ими же о сорвавшемся исследовании говорит
 * компьютер, а до этого фраза стояла здесь литералом — на второй поверхности она была бы
 * копией и разошлась бы с этой при первой правке.
 */
fun failedNote(failed: List<com.point.core.flow.FailedInvestigation>): String? {
    val reasons = failed.map { it.reason.trim() }.filter { it.isNotBlank() }.distinct()
    if (reasons.isEmpty()) return null
    return com.point.core.flow.investigationFailedNote(reasons.joinToString("; "))
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
    Box(
        modifier = Modifier
            .padding(top = 16.dp)
            .widthIn(max = PortalColumnWidth)
            .animateContentSize(tween(220))
            .portalCard(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
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
        // Галочка — знак «Point это знает». Значение неизвестно откуда её не получает:
        // выдуманное не имеет права выглядеть увереннее прочитанного (#948, #940).
        Text(
            text = if (fact.known) "✓" else "·",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = if (fact.known) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
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
                text = if (copied) tr("Скопировано") else value,
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

internal const val FACT_COPIED_SHOWN_MS = 1_600L
