package com.point.core.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.point.core.flow.META_ALT_SUFFIX
import com.point.core.flow.META_ENTITY_PREFIX
import com.point.core.flow.META_LINE_SUFFIX
import com.point.core.flow.alternativesOf
import com.point.core.flow.isDoubtful
import com.point.core.flow.provenanceLabel
import com.point.core.flow.readinessShownFacts
import com.point.core.flow.KIND_ADDRESS
import com.point.core.flow.KIND_DATE
import com.point.core.flow.KIND_EMAIL
import com.point.core.flow.KIND_PHONE
import com.point.core.flow.KIND_URL
import com.point.core.model.ObjectKind
import com.point.core.model.Bubble
import com.point.core.model.CapabilityId
import com.point.core.model.Intent
import com.point.core.model.LatentBubble
import com.point.core.model.PointObject
import com.point.core.model.RelationType
import com.point.core.model.Relation
import com.point.core.model.ValueRef

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FirstScreen(
    obj: PointObject,
    bubbles: List<Bubble>,
    onBubble: (Bubble) -> Unit,
    modifier: Modifier = Modifier,
    message: String? = null,

    messageOutcome: Outcome = Outcome.NONE,

    messageOffer: String? = null,
    onMessageOffer: () -> Unit = {},
    inputPrompt: String? = null,
    inputSuggestions: List<String> = emptyList(),
    onSubmitInput: (String) -> Unit = {},
    onCancelInput: () -> Unit = {},
    items: List<PointObject> = emptyList(),

    itemsTotal: Int = 0,

    itemsTotalAtLeast: Boolean = false,
    onItem: (PointObject) -> Unit = {},
    found: List<PointObject> = emptyList(),
    relations: List<Relation> = emptyList(),
    onFound: (PointObject) -> Unit = {},
    textPreview: String? = null,
    textPreviewTruncated: Boolean = false,
    latent: List<LatentBubble> = emptyList(),
    enriching: List<String> = emptyList(),
    failed: List<com.point.core.flow.FailedInvestigation> = emptyList(),
    working: Boolean = false,

    workingStage: String? = null,
    previewBitmap: ImageBitmap? = null,
    appIconFor: (String) -> ImageBitmap? = { null },

    onHeroTap: (() -> Unit)? = null,

    /** Показанная человеком область — та самая, в которую Point сейчас смотрит (#757). */
    focusPreview: ImageBitmap? = null,
    focused: Boolean = false,
    onClearFocus: () -> Unit = {},

    /**
     * Человеческое имя вопроса знания и показанная область (#1016, #1000): «смотрели — не
     * нашлось» — знание, и человек видит его на телефоне так же, как на компьютере.
     * Вопрос без человеческого имени на экран не выходит (P2).
     */
    questionName: (com.point.core.model.CapabilityId) -> String? = { null },
    focusScope: String? = null,
) {

    val scroll = rememberScrollState()
    Column(
        modifier = modifier
            .fillMaxSize()

            .verticalScroll(scroll)
            .padding(horizontal = 20.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val facts = understoodFacts(obj)

        // Найденное живёт внизу, а не строкой действия наверху (решение владельца 11.08.2026:
        // «сущности вверху имеют одно действие и не то, которое мне надо; хочу видеть их внизу
        // и правильно сгруппированными»). Наверху блок навязывал по одному действию на
        // значение — телефону «Сохранить контакт», номеру «Отследить», — и оно почти никогда
        // не было тем, что человеку нужно. Действия остались там, где им место: у самого
        // объекта, когда в него входят.
        val promoted = found.mapNotNullTo(mutableSetOf()) { factKeyFor(it.state.kind) }

        // Объект, который сам и есть значение, не рассказывает о себе второй раз: внутри
        // телефона «067 636 05 60» строка «Нашёл телефон 067 636 05 60» повторяла заголовок.
        val own = objectVerdict(obj).headline.trim()
        val plainFacts = facts
            .filter { it.key !in promoted }
            .filterNot { sameAsOwnValue(it, own) }
        // Найденное не повторяет показанное выше (#696, #747): телефон, уже стоящий в
        // карточке человека, вторым чипом не приходит, и исправленное значение не двоится
        // со своей же строкой. Правило было отключено при перестройке экрана 11.08.2026 —
        // строкой `val visibleFound = found`, — и полтора дня жило только в тестах.
        val shownValues = remember(plainFacts, own) {
            plainFacts.mapNotNullTo(mutableSetOf()) { it.value?.trim() } + own
        }
        val visibleFound = visibleFoundChips(found, shownValues)

        ObjectHeader(
            obj,
            thinking = enriching.isNotEmpty() || working,
            factCount = facts.size,
            preview = previewBitmap,
            onTap = onHeroTap,
            focusEntry = obj.state.kind == ObjectKind.IMAGE,
        )

        if (focused) {
            Spacer(Modifier.height(12.dp))
            FocusStrip(preview = focusPreview, onClear = onClearFocus)
        }

        WorkingStage(workingStage)

        OutcomeBanner(message, messageOutcome)

        if (message != null && messageOffer != null) {
            Spacer(Modifier.height(10.dp))
            PortalRow(
                title = messageOffer,
                onClick = onMessageOffer,
                modifier = Modifier.widthIn(max = PortalColumnWidth),
            )
        }

        issuedLinkOf(obj.metadata)?.let { link ->
            Spacer(Modifier.height(16.dp))
            LinkCard(url = link, title = "Ссылка на файл", warning = issuedLinkWarning(obj.metadata))
        }

        UnderstoodSection(
            facts = plainFacts,
            enriching = enriching,
            failed = failed,
            questions = com.point.core.flow.openQuestions(obj.metadata, focusScope, questionName),
        )

        if (visibleFound.isNotEmpty() && inputPrompt == null) {
            FoundObjects(
                found = visibleFound,
                relations = relations,
                partShownAbove = false,
                onFound = onFound,
            )
        }

        Spacer(Modifier.height(28.dp))

        if (items.isNotEmpty() && inputPrompt == null) {
            CollectionItems(
                items = items,
                total = itemsTotal,
                atLeast = itemsTotalAtLeast,
                onItem = onItem,
            )
            Spacer(Modifier.height(28.dp))
        }

        if (textPreview != null && inputPrompt == null) {
            TextPreview(
                text = textPreview,
                markdown = obj.mime == "text/markdown",
                truncated = textPreviewTruncated,
            )
            Spacer(Modifier.height(28.dp))
        }

        if (inputPrompt != null) {
            AmendmentInput(
                prompt = inputPrompt,
                onSubmit = onSubmitInput,
                onCancel = onCancelInput,
                suggestions = inputSuggestions,
            )
        } else {

            ObjectActions(
                sections = actionSections(bubbles, useFirst = obj.uri is ValueRef || knowsUsableValue(obj.state)),
                working = working,
                onBubble = onBubble,
                appIconFor = appIconFor,
                unfit = obj.state.has(com.point.core.model.Feature.UNUSABLE),
            )
            if (latent.isNotEmpty()) {
                Spacer(Modifier.height(22.dp))
                LatentHints(latent)
            }
        }
    }

    LaunchedEffect(message) { if (message != null) scroll.animateScrollTo(0) }
}

@Composable
private fun LatentHints(latent: List<LatentBubble>) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = "Почти доступно",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
        )
        latent.forEach { hint ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = bubbleIcon(hint.icon),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = "${hint.title} · ${hint.missing}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }
        }
    }
}

@Composable
private fun WorkingStage(stage: String?) {

    var shown by remember { mutableStateOf("") }
    LaunchedEffect(stage) { if (stage != null) shown = stage }

    AnimatedVisibility(
        visible = stage != null,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 14.dp),
        ) {
            ThinkingDot()
            Spacer(Modifier.width(9.dp))
            Text(
                text = shown,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun FoundObjects(
    found: List<PointObject>,
    relations: List<Relation>,
    partShownAbove: Boolean,
    onFound: (PointObject) -> Unit,
) {
    Spacer(Modifier.height(16.dp))
    Column(
        modifier = Modifier.widthIn(max = 340.dp).fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = foundHeader(found.size, partShownAbove),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        found.forEach { obj ->
            key(obj.id) {
                Surface(
                    onClick = { onFound(obj) },
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(
                            imageVector = kindIcon(obj.state.kind),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = foundHeadline(obj),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = listOfNotNull(
                                    kindLabel(obj.state.kind),
                                    roleOf(obj, relations),

                                    provenanceLabel(obj.provenance),

                                    "возможно".takeIf { isDoubtful(obj.metadata) },
                                ).joinToString(" · "),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )

                            foundCaption(obj)?.let { said ->
                                Text(
                                    text = said,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }

                            otherReading(obj)?.let { other ->
                                Text(
                                    text = "или: $other",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Заголовок найденного объекта — текущее значение его факта, а не `uri`:
 * после правки человеком uri продолжает хранить прежнее значение (ADR-0001 §4 —
 * одно значение в двух ролях; носитель истины — факт).
 */
/**
 * Строка знания повторяет само значение объекта (#932).
 *
 * Внутри телефона «067 636 0560» строка «Нашёл телефон 067 636 0560» говорила бы то же
 * самое дважды. Сравнение по тексту здесь мало: тот же номер приходит записанным и
 * `+380676360560`, и `067 636 05 60`. Одинаковость номера считает библиотека.
 */
private fun sameAsOwnValue(fact: com.point.core.ui.UnderstoodFact, own: String): Boolean {
    val value = fact.value?.trim() ?: return false
    if (value == own) return true
    return fact.key == "phone" && com.point.core.flow.PhoneNumbers.same(value, own)
}

fun foundHeadline(obj: PointObject): String =
    obj.metadata.entries.firstOrNull { (key, _) ->
        (key.startsWith(META_ENTITY_PREFIX) || key.startsWith(com.point.core.flow.META_GRAPH_ROLE_PREFIX)) &&
            !com.point.core.flow.isAnnotationKey(key) && !com.point.core.flow.isStateKey(key)
    }?.takeIf { it.value.isNotBlank() }

        // Номер и здесь показывается по-человечески (#932): чип, заголовок объекта и строка
        // знания говорят об одном номере одним видом — иначе одно и то же значение выглядит
        // на экране двумя разными.
        ?.let { (key, value) -> com.point.core.flow.shownKnowledge(key, value) }

        // Результат чужого исполнителя (ПК) не несёт entity/role-фактов — только имя:
        // без этого запасного шага чип показывал путь до scratch-файла (#681).
        ?: obj.metadata["name"]?.takeIf { it.isNotBlank() }

        // Узел-значение несёт значение прямо в `uri` — это и есть то, что читает человек.
        ?: (obj.uri as? com.point.core.model.ValueRef)?.value?.takeIf { it.isNotBlank() }

        // А вот файл в служебной папке заголовком не бывает (#1038): у взятого фрагмента имени
        // не было вовсе, и человек читал `/data/user/0/com.point/files/scratch/7fe5bba1…` —
        // строку длинную, обрезанную многоточием и бесполезную даже как адрес.
        ?: kindLabel(obj.state.kind)

/**
 * Chip прячется, только если ЕГО ФАКТ уже показан строкой выше: сравнение по `uri`
 * после правки человеком прятало не тот дубль (§4 — одно значение, не две копии).
 * Телефон, уже подписанный человеком (#653), вторым узлом не показывается — знание
 * не удалено, оно внутри человека.
 */
/**
 * Число рядом с «Нашёл» — только когда всё найденное собрано в одном списке.
 * На визитке телефон уезжал в строку «Сохранить контакт», и человек читал
 * «Нашёл · 2», видя три значения (#696). Нет числа — нет и вранья.
 */
fun foundHeader(count: Int, partShownAbove: Boolean): String =
    if (partShownAbove) "Нашёл" else "Нашёл · $count"

fun visibleFoundChips(found: List<PointObject>, shownValues: Set<String>): List<PointObject> {
    val claimed = found.filter { it.state.kind == com.point.core.flow.KIND_PERSON }
        .mapNotNull { person -> person.metadata[com.point.core.flow.META_ENTITY_PHONE] }

    // Один номер, записанный по-разному, — один номер (#932): у человека внутри он стоит
    // `+380 66 526 2706`, а узлом рождён `+380665262706`. Тождество считает библиотека, а не
    // текст, — иначе тот же номер показывался бы вторым чипом.
    return found.filter { chip ->
        val headline = foundHeadline(chip).trim()
        val phone = chip.state.kind == KIND_PHONE
        val repeated = headline in shownValues ||
            (phone && shownValues.any { com.point.core.flow.PhoneNumbers.same(headline, it) })
        !repeated && !(phone && claimed.any { com.point.core.flow.PhoneNumbers.same(headline, it) })
    }
}

/**
 * Подпись найденного — строка документа, из которой вычитано значение (#782): видно,
 * что это за день, но значением подпись не является и заголовком чипа не становится.
 */
fun foundCaption(obj: PointObject): String? {
    val value = foundHeadline(obj).trim()
    return obj.metadata.entries
        .firstOrNull { (key, said) -> key.endsWith(META_LINE_SUFFIX) && said.isNotBlank() }
        ?.value
        ?.takeIf { it.trim() != value }
}

/**
 * След Focus между кадром и действиями (#757, решение владельца — вариант A).
 *
 * Focus меняет поведение всех следующих действий: «Понять» и «Распознать текст» читают только
 * показанную область. Знание, меняющее поведение, не может быть невидимым — иначе человек
 * показал область и не знает, применилось ли это. Здесь видно и что Point смотрит в область,
 * и саму область, и как это снять одним движением.
 */
@Composable
internal fun FocusStrip(preview: ImageBitmap?, onClear: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.widthIn(max = PortalColumnWidth).fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            preview?.let {
                Image(
                    bitmap = it,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)),
                )
            }
            Text(
                text = FOCUS_HERE,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onClear) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = FOCUS_DROP,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}

const val FOCUS_HERE = "Смотрю сюда"

const val FOCUS_DROP = "Смотреть на весь объект"

fun otherReading(obj: PointObject): String? =
    obj.metadata.keys.firstOrNull { it.endsWith(META_ALT_SUFFIX) }

        // Одно и то же значение, записанное иначе, расхождением не является (#1011): строка
        // «или:» повторяла победителя — `(918) 682-1561` наверху и `918-682-1561` под ним, —
        // а настоящая альтернатива до человека не доходила.
        ?.let { com.point.core.flow.disputedValues(obj.metadata, it.removeSuffix(META_ALT_SUFFIX)) }
        ?.firstOrNull { it.trim() != foundHeadline(obj).trim() }

private fun roleOf(obj: PointObject, relations: List<Relation>): String? =
    relations.asSequence().filter { it.fromId == obj.id }.mapNotNull { relationLabel(it.type) }.firstOrNull()

internal fun relationLabel(type: RelationType): String? = when (type) {
    RelationType.SENDER -> "отправитель"
    RelationType.RECEIVER -> "получатель"
    RelationType.CARRIER -> "перевозчик"
    RelationType.ISSUED_BY -> "выдал документ"
    RelationType.SIGNED_BY -> "подписал"
    else -> null
}

internal fun factKeyFor(kind: ObjectKind): String? = when (kind) {
    KIND_PHONE -> "phone"
    KIND_EMAIL -> "email"
    KIND_URL -> "url"
    KIND_ADDRESS -> "address"
    KIND_DATE -> "date"
    else -> null
}

const val COLLECTION_PAGE = 25

fun collectionLabel(shown: Int, total: Int, atLeast: Boolean): String = when {
    total <= shown && !atLeast -> "Содержимое · ${grouped(shown)}"
    total <= shown -> "Содержимое · ${grouped(shown)}, и это не всё"
    atLeast -> "Содержимое · ${grouped(shown)} из более чем ${grouped(total)}"
    else -> "Содержимое · ${grouped(shown)} из ${grouped(total)}"
}

internal fun grouped(n: Int): String =
    n.toString().reversed().chunked(3).joinToString(" ").reversed()

@Composable
private fun CollectionItems(
    items: List<PointObject>,
    total: Int,
    atLeast: Boolean,
    onItem: (PointObject) -> Unit,
) {

    var page by rememberSaveable(items.size) { mutableStateOf(COLLECTION_PAGE) }
    Text(
        text = collectionLabel(items.size, total, atLeast),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(12.dp))
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items.take(page).forEach { item ->
            Surface(
                onClick = { onItem(item) },
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                tonalElevation = 1.dp,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        imageVector = kindIcon(item.state.kind),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp),
                    )
                    Text(
                        text = item.metadata["name"] ?: kindLabel(item.state.kind),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        if (page < items.size) {
            TextButton(onClick = { page += COLLECTION_PAGE }) {
                Text("Показать ещё · ${grouped(items.size - page)}")
            }
        }
    }
}

const val TEXT_PREVIEW_HEAD = 2_000

const val COLLAPSED_PREVIEW_LINES = 3

/**
 * Предел, до которого предпросмотр читает объект (см. вызывающую сторону — она читает
 * ровно столько же). Раньше этот предел знала только вызывающая сторона, а кнопка
 * «Показать целиком» обещала «целиком», даже упёршись в него (#682/#683).
 */
const val TEXT_PREVIEW_LOAD_LIMIT = 100_000

fun textPreviewHead(text: String, limit: Int = TEXT_PREVIEW_HEAD): String {
    if (text.length <= limit) return text
    val head = text.take(limit)
    val cut = head.lastIndexOf('\n')
    return if (cut > limit / 2) head.substring(0, cut) else head
}

/**
 * «Показать целиком» показывает целиком либо честно называет, сколько показывает
 * (#682/#683): если сам предпросмотр упёрся в свой предел чтения, кнопка не обещает
 * «целиком» — за пределом может быть ещё, и число становится нижней границей.
 *
 * Скрытых символов может не быть вовсе, а текст всё равно обрезан — тремя строками на
 * экране (#871). Тогда числа нет: обещать «ещё 0 символов» — врать про то, что человек
 * видит своими глазами.
 */
fun expandTextLabel(hiddenChars: Int, atLimit: Boolean): String = when {
    hiddenChars <= 0 -> "Показать целиком"
    atLimit -> "Показать больше · ещё не менее ${grouped(hiddenChars)} символов"
    else -> "Показать целиком · ещё ${grouped(hiddenChars)} символов"
}

/** Подпись под развёрнутым текстом, который сам упёрся в предел чтения. */
fun truncatedPreviewNotice(shownChars: Int): String =
    "Показаны первые ${grouped(shownChars)} символов — в объекте может быть ещё"

@Composable
private fun TextPreview(text: String, markdown: Boolean = false, truncated: Boolean = false) {

    var expanded by rememberSaveable(text.length) { mutableStateOf(false) }

    // Текст обрезается дважды: по символам (голова) и по строкам на экране. Раньше кнопка
    // знала только про первое, и всё, что короче двух тысяч символов, но длиннее трёх
    // строк, обрывалось многоточием без всякого способа раскрыть (#871).
    var clipped by rememberSaveable(text.length) { mutableStateOf(false) }
    val head = remember(text) { textPreviewHead(text) }
    val shown = if (expanded) text else head

    val rendered = remember(shown, markdown) { if (markdown) markdownToAnnotated(shown) else AnnotatedString(shown) }
    Text(
        text = "Текст",
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(12.dp))
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        SelectionContainer {
            Text(
                text = rendered,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,

                maxLines = if (expanded) Int.MAX_VALUE else COLLAPSED_PREVIEW_LINES,
                overflow = TextOverflow.Ellipsis,
                onTextLayout = { if (it.hasVisualOverflow) clipped = true },
            )
        }
    }
    if (clipped || head.length < text.length) {
        TextButton(onClick = { expanded = !expanded }) {
            Text(if (expanded) "Свернуть" else expandTextLabel(text.length - head.length, truncated))
        }
    }
    if (expanded && truncated) {
        Text(
            text = truncatedPreviewNotice(text.length),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

internal const val FOCUS_ENTRY_LABEL = "Выделить область"

/** Значок в углу превью: одна иконка, без текста — не шумит, но говорит, что обводка есть. */
@Composable
private fun FocusEntryMark(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primary,

        // Ободок цветом экрана отделяет значок от превью: без него яркий круг на светлом
        // снимке сливается краем и читается пятном, а не кнопкой.
        border = BorderStroke(3.dp, MaterialTheme.colorScheme.background),
        modifier = modifier.size(44.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = bubbleIcon("find"),
                contentDescription = FOCUS_ENTRY_LABEL,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

@Composable
private fun ObjectHeader(
    obj: PointObject,
    thinking: Boolean = false,
    factCount: Int = 0,
    preview: ImageBitmap? = null,
    onTap: (() -> Unit)? = null,

    /** Обводка — целая функция, а тап по картинке о ней не сообщает (#641). */
    focusEntry: Boolean = false,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {

        // Портал один на любой объект: круг одного размера. Прежде круглым он был только
        // у снимка, а всё остальное садилось скруглённым квадратом и меньшего размера —
        // вместе с ним прыгало и кольцо портала. Форма объекта не меняет форму двери.
        val headerSize = PortalPreviewSize
        val headerShape = CircleShape

        Box(
            contentAlignment = Alignment.Center,

            modifier = Modifier.clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = onTap != null,
            ) { onTap?.invoke() },
        ) {

            val glow = ((if (thinking) 0.9f else 0.62f) + 0.09f * factCount.coerceAtMost(4)).coerceAtMost(1f)
            Portal(size = headerSize + 68.dp, intensity = glow)

            AliveSurface(
                kind = obj.state.kind,
                thinking = thinking,
                understanding = auraLevel(factCount),
                shape = headerShape,
                size = headerSize,
            ) {
                if (preview != null) {
                    RoundPreview(
                        image = preview,
                        size = headerSize,
                        contentDescription = obj.metadata["name"] ?: obj.state.kind.name,
                    )
                } else if (kindMarkOf(obj) != KindMark.UNKNOWN) {

                    // Марка на каждый вид (#825): экран объекта одинаково живой, чем бы объект
                    // ни был. Раньше нарисованной была одна таблица, а всё прочее брало плоскую
                    // системную иконку — и объект то оживал, то становился строкой из списка
                    // приложений.
                    KindMarkIcon(
                        mark = kindMarkOf(obj),
                        size = headerSize * MARK_IN_CIRCLE,
                        motion = rememberMotionEnabled(),
                        contentDescription = obj.metadata["name"] ?: kindMarkLabel(kindMarkOf(obj)),
                    )
                } else {
                    Surface(
                        shape = CircleShape,

                        // Та же пара, что у аватара «Недавнего»: тёмная подложка и светлый
                        // знак. Прежний `secondary` — светло-серый, и на диаметре портала
                        // он превращался в яркое пятно, чужое тёмному кольцу.
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shadowElevation = 0.dp,
                        modifier = Modifier.size(headerSize),
                    ) {
                        Icon(
                            imageVector = kindIcon(obj.state.kind),
                            contentDescription = obj.state.kind.name,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier
                                .padding(headerSize * ICON_INSET)
                                .fillMaxSize(),
                        )
                    }
                }
            }

            // Вход в обводку рисуется ПОВЕРХ превью и в цвете портала (#798, решение
            // владельца 11.08.2026: «вынести вперёд и подсветить»). Прежде он лежал под
            // кругом почти прозрачным пятном и на тёмном снимке терялся: за ним прячутся
            // три способности — «Понять область», «Взять фрагмент» и «Замазать».
            if (focusEntry && onTap != null) {
                Box(
                    modifier = Modifier.size(headerSize),
                    contentAlignment = Alignment.BottomEnd,
                ) {
                    FocusEntryMark(onClick = onTap)
                }
            }
        }
        Spacer(Modifier.height(14.dp))

        val verdict = objectVerdict(obj)
        Text(
            text = verdict.headline,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        // Вид крупно, имя тише, мера самым тихим (#879). Раньше три строки шли почти одним
        // весом, и глаз не знал, что здесь главное.
        verdict.subline?.let { sub ->
            Text(
                text = sub,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }

        verdict.measure?.let { measure ->
            Text(
                text = measure,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.62f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** Enter отправляет только осмысленный ответ (#645): пустой ввод — не ответ. */
internal fun meaningfulAmendment(text: String): String? = text.takeIf { it.isNotBlank() }

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun AmendmentInput(
    prompt: String,
    onSubmit: (String) -> Unit,
    onCancel: () -> Unit,
    suggestions: List<String> = emptyList(),
) {

    var text by rememberSaveable { mutableStateOf("") }

    // Решение владельца (#645): Enter отправляет, автофокус, без многострочности —
    // значения здесь короткие, а кнопка «Готово» пряталась под клавиатурой.
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    val submit = { meaningfulAmendment(text)?.let(onSubmit) ?: Unit }
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = prompt,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
        if (suggestions.isNotEmpty()) {

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                suggestions.forEach { suggestion ->
                    SuggestionChip(
                        onClick = { onSubmit(suggestion) },
                        label = { Text(suggestion) },
                    )
                }
            }
        }
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onCancel) { Text("Отмена") }
            Button(onClick = { onSubmit(text) }) { Text("Готово") }
        }
    }
}

/**
 * Квадрат, вписанный в круг, — сторона не больше диаметра/√2. Рисованный знак садится
 * с запасом, чтобы круг его не срезал (#650-соседнее: единый портал у всех объектов).
 */
private const val MARK_IN_CIRCLE = 0.62f

/** Отступ значка внутри круга: та же зрительная величина, что была у квадрата 96 dp. */
private const val ICON_INSET = 0.27f
