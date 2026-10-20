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
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
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
import androidx.compose.foundation.background
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

    /**
     * Перестановка страниц набора (#1207): страница и шаг (−1 — выше, +1 — ниже). Нет
     * обработчика — нет и стрелок: экран не показывает ручку, за которой ничего не стоит.
     */
    onMoveItem: ((PointObject, Int) -> Unit)? = null,

    /** Миниатюра страницы набора — тем же чтением, что и остальные превью; без неё — знак вида. */
    itemPreviewFor: suspend (PointObject) -> ImageBitmap? = { null },
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
        val verdict = objectVerdict(obj)

        // Вещь → её смыслы → естественные шаги. Найденное значение приходит со своими
        // продолжениями — «Телефон · 067 123 4567»: позвонить, написать, сохранить контакт, —
        // а действия самой вещи и её понимания стоят ниже, после её содержимого.
        //
        // Решение владельца 11.08.2026 убрало наверх не значения, а строку «одно действие на
        // значение, и не то, которое мне надо». Здесь у значения все его продолжения, и
        // сгруппированы они по значению — ровно то «правильно сгруппированными», о котором
        // он просил.
        val sections = if (inputPrompt == null) {
            actionSections(bubbles, obj.state, thingTitle(obj), thingIsValue(obj), makeTitle(obj))
        } else {
            emptyList()
        }
        val valueSections = sections.filter { it.topic == SectionTopic.VALUE }
        val ownSections = sections.filter { it.topic != SectionTopic.VALUE }
        val sharedReason = com.point.core.flow.sharedUnusableReason(bubbles.map { it.unusableReason })

        // Одно значение — одно место (#696, #747): значение, у которого есть продолжения,
        // стоит заголовком своего раздела и не повторяется ни строкой знания, ни чипом.
        val continued = valueSections.mapTo(mutableSetOf()) { understoodKeyOf(it.key) }
        val continuedValues = valueSections
            .flatMap { listOfNotNull(rawValueOf(obj, it.key), it.value) }
            .map(String::trim)

        // Найденное живёт внизу, а не строкой действия наверху (решение владельца 11.08.2026:
        // «сущности вверху имеют одно действие и не то, которое мне надо; хочу видеть их внизу
        // и правильно сгруппированными»). Наверху блок навязывал по одному действию на
        // значение — телефону «Сохранить контакт», номеру «Отследить», — и оно почти никогда
        // не было тем, что человеку нужно. Действия остались там, где им место: у самого
        // объекта, когда в него входят.
        val promoted = found.mapNotNullTo(mutableSetOf()) { factKeyFor(it.state.kind) }

        // Объект, который сам и есть значение, не рассказывает о себе второй раз: внутри
        // телефона «067 636 05 60» строка «Нашёл телефон 067 636 05 60» повторяла заголовок.
        val own = verdict.headline.trim()
        val plainFacts = facts
            .filter { it.key !in promoted && it.key !in continued }
            .filterNot { sameAsOwnValue(it, own) }
        // Найденное не повторяет показанное выше (#696, #747): телефон, уже стоящий в
        // карточке человека, вторым чипом не приходит, и исправленное значение не двоится
        // со своей же строкой. Правило было отключено при перестройке экрана 11.08.2026 —
        // строкой `val visibleFound = found`, — и полтора дня жило только в тестах.
        val shownValues = remember(plainFacts, own, continuedValues) {
            plainFacts.mapNotNullTo(mutableSetOf()) { it.value?.trim() } + own + continuedValues
        }
        // Сделанное — не найденное (P4): результат действия стоит у исходника своим блоком
        // «Получилось», а у результата — «Сделано из» с дорогой назад. В общем списке
        // «Нашёл» они значились находками, хотя Point их не нашёл, а сделал.
        val made = com.point.core.flow.madeOf(obj.id, found, relations)
        val madeFrom = com.point.core.flow.madeFromOf(obj.id, found, relations)
        val visibleFound = visibleFoundChips(
            found.filterNot { node -> node in made || node.id == madeFrom?.id },
            shownValues,
        )

        // Заголовок раздела значения — само значение, с тем, что про него известно. Смысл,
        // который уже сказан подписью объекта («Встреча · Созвон с Анной»), не повторяется.
        fun headOf(section: ActionSection<Bubble>): ValueHead {
            val value = section.value?.takeUnless { it == verdict.headline || it == verdict.subline }
            val raw = rawValueOf(obj, section.key)
            return ValueHead(
                name = section.title,
                value = value,
                copy = value?.let { raw ?: it },
                known = facts.firstOrNull { it.key == understoodKeyOf(section.key) }?.known ?: true,
                other = otherReadingOf(obj.metadata, section.key),
            )
        }

        ObjectHeader(
            obj,
            thinking = enriching.isNotEmpty() || working,
            factCount = facts.size,
            preview = previewBitmap,
            onTap = onHeroTap,
            focusEntry = obj.state.kind == ObjectKind.IMAGE,
        )

        madeFrom?.let { source ->
            Spacer(Modifier.height(8.dp))
            MadeFromRow(source = source, onOpen = { onFound(source) })
        }

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

        if (made.isNotEmpty() && inputPrompt == null) {
            MadeHere(made = made, onOpen = onFound)
        }

        issuedLinkOf(obj.metadata)?.let { link ->
            Spacer(Modifier.height(16.dp))
            LinkCard(url = link, title = "Ссылка на файл", warning = issuedLinkWarning(obj.metadata))
        }

        if (valueSections.isNotEmpty()) {
            Spacer(Modifier.height(24.dp))
            ObjectActions(
                sections = valueSections,
                shared = sharedReason,
                working = working,
                onBubble = onBubble,
                appIconFor = appIconFor,
                head = ::headOf,
            )
        }

        UnderstoodSection(facts = plainFacts, enriching = enriching, failed = failed)

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
                onMove = onMoveItem?.takeIf { reorderablePages(items) },
                previewFor = itemPreviewFor,
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
                sections = ownSections,
                shared = sharedReason,
                working = working,
                onBubble = onBubble,
                appIconFor = appIconFor,
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

/**
 * «Получилось» — сделанное из этой вещи, у неё же (P4).
 *
 * Стоит сразу под словами о результате: человек нажал действие здесь и здесь же видит, что
 * вышло. Вход в результат — касанием, когда он нужен; исходник при этом не пропадает.
 */
@Composable
private fun MadeHere(made: List<PointObject>, onOpen: (PointObject) -> Unit) {
    Column(
        modifier = Modifier
            .padding(top = 14.dp)
            .widthIn(max = PortalColumnWidth)
            .fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SectionLabel(MADE_HERE_LABEL)
        made.asReversed().forEach { result ->
            key("made:" + result.id) {
                Surface(
                    onClick = { onOpen(result) },
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
                            imageVector = kindIcon(result.state.kind),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = foundHeadline(result),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = kindLabel(result.state.kind),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            text = "открыть ›",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}

/** «Сделано из …» — дорога от результата назад к исходнику, прямо под вещью. */
@Composable
private fun MadeFromRow(source: PointObject, onOpen: () -> Unit) {
    Text(
        text = "$MADE_FROM_LABEL ${foundHeadline(source)} ›",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .widthIn(max = PortalColumnWidth)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onOpen)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

const val MADE_HERE_LABEL = "Получилось"
const val MADE_FROM_LABEL = "Сделано из"

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
        // Однородная сотня — строкой-классом (#1058): знание не урезается, меняется
        // представление. Раскрытый класс можно свернуть обратно.
        val expanded = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(setOf<com.point.core.model.ObjectKind>()) }
        val rows = foldFound(found)
        rows.forEach { row ->
            if (row is FoundRow.Group && row.kind !in expanded.value) {
                key("fold:" + row.kind.name) {
                    Surface(
                        onClick = { expanded.value = expanded.value + row.kind },
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
                                imageVector = kindIcon(row.kind),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp),
                            )
                            Text(
                                text = kindLabel(row.kind) + " · " + row.items.size,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = "раскрыть ›",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                return@forEach
            }
            val group = (row as? FoundRow.Group)?.items ?: listOf((row as FoundRow.Single).obj)
            if (row is FoundRow.Group) {
                key("unfold:" + row.kind.name) {
                    Text(
                        text = kindLabel(row.kind) + " · " + row.items.size + " — свернуть",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { expanded.value = expanded.value - row.kind }
                            .padding(vertical = 4.dp),
                    )
                }
            }
            group.forEach { obj ->
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

                                    ownerOfFound(obj, relations, found),

                                    foundProvenance(obj),

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
}

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

/**
 * Откуда взялось найденное — подпись только у узла-значения (#1421).
 *
 * У объекта-файла в `provenance` лежит сильнейшее происхождение его фактов: исходник с
 * контактами, найденными правилами, стоял в «Нашёл» у результата с подписью «выведено
 * правилом» — как будто правилом выведен сам исходник. Файл ничем не выведен: он дан.
 */
fun foundProvenance(obj: PointObject): String? =
    if (isValueNode(obj)) provenanceLabel(obj.provenance) else null

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

/**
 * Ответ на вопрос, заданный областью (#1000): посмотрели — и ничего.
 *
 * Не сбой, а знание: прошенное получает слово, в отличие от незапрошенного «не нашлось»,
 * которое экран по решению владельца не показывает (#1016).
 */
const val FOCUS_NOTHING_FOUND = "В области ничего не нашлось"

/**
 * Расхождение — это другое значение, а не та же запись другими знаками (#1011).
 *
 * Строка «или:» показывала первый элемент списка, и им часто оказывалось то же самое, что
 * уже выбрано: «(918) 682-1561» и «918-682-1561». Тождество здесь спрашивается тем же
 * `sameFact`, что и в движке, — двух мерок одного вопроса в Point не бывает (#1136).
 */
fun otherReading(obj: PointObject): String? {
    val altKey = obj.metadata.keys.firstOrNull { it.endsWith(META_ALT_SUFFIX) } ?: return null
    val key = altKey.removeSuffix(META_ALT_SUFFIX)
    return otherReadingOf(obj.metadata, key, shown = obj.metadata[key] ?: foundHeadline(obj))
}

/** Другое прочтение значения [key] — то, что человеку нужно видеть рядом с ним (P8). */
internal fun otherReadingOf(
    metadata: Map<String, String>,
    key: String,
    shown: String? = metadata[key],
): String? {
    val current = shown?.trim() ?: return null
    return alternativesOf(metadata, key)
        .map(String::trim)
        .firstOrNull { it.isNotBlank() && !com.point.core.flow.sameFact(key, it, current) }
}

/**
 * Само значение факта — то, что человек вставит, скопировав его: номер без подписи вида,
 * ссылка со схемой. Только у значений: у смысла вещи («встреча») значения нет, есть суть.
 */
internal fun rawValueOf(obj: PointObject, factKey: String): String? =
    obj.metadata[factKey]?.takeIf { factKey.startsWith(META_ENTITY_PREFIX) && it.isNotBlank() }

/**
 * Ключ строки знания (`understoodFacts`) для ключа факта: «entity.phone» — «phone».
 * Раздел значения и строка знания об одном значении узнают друг друга по нему.
 */
internal fun understoodKeyOf(factKey: String): String = when (factKey) {
    com.point.core.flow.META_SHOT_AT -> "shot-at"
    com.point.core.flow.META_SEMANTIC_TYPE -> "semantic"
    else -> factKey.removePrefix(META_ENTITY_PREFIX)
}

private fun roleOf(obj: PointObject, relations: List<Relation>): String? =
    relations.asSequence().filter { it.fromId == obj.id }.mapNotNull { relationLabel(it.type) }.firstOrNull()

/**
 * Чьё это найденное (#1176).
 *
 * Связь меняет смысл строки: номер отправителя и номер получателя выглядят одинаково, и без
 * имени рядом человек не знает, кому звонит. Имя берётся у самого узла стороны — второй копии
 * значения не заводится. Связи нет — и подписи нет: догадка вместо знания хуже молчания.
 *
 * Связь называется словом, а не голым именем: строка свойств перечисляет категории — вид,
 * роль, происхождение, — и имя между ними читалось как ещё одна: «Место · Лумброван»
 * выглядело так, будто место называется Лумброван. Роль стороны уже известна из её узла.
 */
internal fun ownerOfFound(obj: PointObject, relations: List<Relation>, found: List<PointObject>): String? =
    relations.asSequence()
        .filter { it.fromId == obj.id && it.type == RelationType.BELONGS_TO }
        .mapNotNull { relation -> found.firstOrNull { it.id == relation.toId } }
        .mapNotNull { party -> foundHeadline(party).trim().takeIf { it.isNotBlank() }?.let { whoseLabel(party) + it } }
        .firstOrNull()

/** «Чей» — ролью стороны, когда она названа: номер отправителя — «отправителя: …». */
private fun whoseLabel(party: PointObject): String {
    val role = party.metadata.keys.firstNotNullOfOrNull { com.point.core.flow.roleOfKey(it) }?.relation
    return when (role) {
        RelationType.SENDER -> "отправителя: "
        RelationType.RECEIVER -> "получателя: "
        RelationType.CARRIER -> "перевозчика: "
        RelationType.ISSUED_BY -> "выдавшего документ: "
        else -> "чей: "
    }
}

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

/**
 * Набор из нескольких страниц — снимков, PDF, текстов — можно переставлять (#1207): их
 * порядок читают «В Excel» и «Сканировать в PDF». Одна страница среди файлов архива
 * набором страниц не является: переставлять там нечего.
 */
fun reorderablePages(items: List<PointObject>): Boolean =
    items.count { it.state.kind in com.point.core.flow.PAGE_KINDS } >= 2

const val PAGE_UP = "Страницу выше"

const val PAGE_DOWN = "Страницу ниже"

@Composable
private fun CollectionItems(
    items: List<PointObject>,
    total: Int,
    atLeast: Boolean,
    onItem: (PointObject) -> Unit,
    onMove: ((PointObject, Int) -> Unit)?,
    previewFor: suspend (PointObject) -> ImageBitmap?,
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
        // Стрелки ходят в пределах показанного: страница не уезжает в скрытую часть списка
        // за «Показать ещё» — там её не видно и вернуть нечем.
        val shown = items.take(page)
        shown.forEachIndexed { index, item ->
            // Строка привязана к странице, а не к месту в списке: после перестановки
            // миниатюра едет вместе со страницей, а не читается заново.
            key(item.id) {
                CollectionItemRow(
                    item = item,
                    onClick = { onItem(item) },
                    previewFor = previewFor,
                    onUp = onMove?.takeIf { index > 0 }?.let { move -> { move(item, -1) } },
                    onDown = onMove?.takeIf { index < shown.lastIndex }?.let { move -> { move(item, +1) } },
                    arrows = onMove != null,
                )
            }
        }
        if (page < items.size) {
            TextButton(onClick = { page += COLLECTION_PAGE }) {
                Text(showMoreLabel(items.size - page))
            }
        }
    }
}

@Composable
private fun CollectionItemRow(
    item: PointObject,
    onClick: () -> Unit,
    previewFor: suspend (PointObject) -> ImageBitmap?,
    onUp: (() -> Unit)?,
    onDown: (() -> Unit)?,
    arrows: Boolean,
) {
    var thumb by remember(item.id) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(item.id) { thumb = previewFor(item) }
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = if (arrows) 4.dp else 14.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Место под миниатюру одно и то же, пришла она или нет: строка не прыгает по
            // высоте, когда снимок дочитался.
            Box(modifier = Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                val shown = thumb
                if (shown != null) {
                    Image(
                        bitmap = shown,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)),
                    )
                } else {
                    Icon(
                        imageVector = kindIcon(item.state.kind),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
            Text(
                text = item.metadata["name"] ?: kindLabel(item.state.kind),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (arrows) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { onUp?.invoke() }, enabled = onUp != null) {
                        Icon(Icons.Rounded.KeyboardArrowUp, contentDescription = PAGE_UP)
                    }
                    IconButton(onClick = { onDown?.invoke() }, enabled = onDown != null) {
                        Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = PAGE_DOWN)
                    }
                }
            }
        }
    }
}

const val COLLAPSED_PREVIEW_LINES = 3

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
            Text(if (expanded) COLLAPSE_LABEL else expandTextLabel(text.length - head.length, truncated))
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

        // Героем экрана стоит сама вещь, а не дверь в неё. Снимок и страница показываются
        // целиком и в своих пропорциях: прежде их вырезали кругом внутри светящегося кольца
        // портала, и человек видел не свою фотографию, а её середину в чужой рамке. Портал —
        // знак границы, через которую вещь пришла и уйдёт, а не рама для неё.
        val frame = heroFrameFor(preview)
        val shape = RoundedCornerShape(HERO_CORNER)

        Box(
            contentAlignment = Alignment.Center,

            modifier = Modifier.clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = onTap != null,
            ) { onTap?.invoke() },
        ) {
            AliveSurface(
                kind = obj.state.kind,
                thinking = thinking,
                understanding = auraLevel(factCount),
                shape = shape,
                size = frame.width,
                height = frame.height,
            ) {
                if (preview != null) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(frame.width, frame.height)
                            .background(MaterialTheme.colorScheme.surface),
                    ) {
                        Image(
                            bitmap = preview,
                            contentDescription = obj.metadata["name"] ?: obj.state.kind.name,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.size(frame.width, frame.height),
                        )
                    }
                } else if (kindMarkOf(obj) != KindMark.UNKNOWN) {

                    // Марка на каждый вид (#825): экран объекта одинаково живой, чем бы объект
                    // ни был. Раньше нарисованной была одна таблица, а всё прочее брало плоскую
                    // системную иконку — и объект то оживал, то становился строкой из списка
                    // приложений.
                    KindMarkIcon(
                        mark = kindMarkOf(obj),
                        size = frame.width * MARK_IN_FRAME,
                        motion = rememberMotionEnabled(),
                        contentDescription = obj.metadata["name"] ?: kindMarkLabel(kindMarkOf(obj)),
                    )
                } else {
                    Surface(
                        shape = shape,

                        // Та же пара, что у аватара «Недавнего»: тёмная подложка и светлый знак.
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shadowElevation = 0.dp,
                        modifier = Modifier.size(frame.width, frame.height),
                    ) {
                        Icon(
                            imageVector = kindIcon(obj.state.kind),
                            contentDescription = obj.state.kind.name,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier
                                .padding(frame.width * ICON_INSET)
                                .fillMaxSize(),
                        )
                    }
                }
            }

            // Вход в обводку рисуется ПОВЕРХ превью и в цвете акцента (#798, решение
            // владельца 11.08.2026: «вынести вперёд и подсветить»). Прежде он лежал под
            // кругом почти прозрачным пятном и на тёмном снимке терялся: за ним прячутся
            // три способности — «Понять область», «Взять фрагмент» и «Замазать».
            if (focusEntry && onTap != null) {
                Box(
                    modifier = Modifier.size(frame.width + 12.dp, frame.height + 12.dp),
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

/** Рисованный знак вида и есть лицо вещи без снимка: рамку он занимает почти целиком. */
private const val MARK_IN_FRAME = 0.84f

/** Отступ значка внутри рамки: та же зрительная величина, что была у квадрата 96 dp. */
private const val ICON_INSET = 0.27f

private val HERO_CORNER = 18.dp

/** Самое большее, что вещь занимает на первом экране: первое действие не уезжает за край (#879). */
private val HERO_MAX_WIDTH = 280.dp
private val HERO_MAX_HEIGHT = 200.dp

/** Узкий кадр (чек, длинный скриншот) не превращается в щель: рамка не уже этого. */
private val HERO_MIN_SIDE = 96.dp

internal data class HeroFrame(val width: androidx.compose.ui.unit.Dp, val height: androidx.compose.ui.unit.Dp)

/**
 * Рамка вещи на первом экране — в пропорциях самой вещи.
 *
 * Снимок вписывается целиком в [HERO_MAX_WIDTH] × [HERO_MAX_HEIGHT] без обрезки. Кадр,
 * который вышел бы уже [HERO_MIN_SIDE], получает рамку этой ширины, а сам садится в её
 * середину: вещь видна целиком, а не щелью. Без снимка — квадрат знака вида.
 */
internal fun heroFrameFor(preview: ImageBitmap?): HeroFrame {
    if (preview == null || preview.width <= 0 || preview.height <= 0) {
        return HeroFrame(PortalPreviewSize, PortalPreviewSize)
    }
    val aspect = preview.width.toFloat() / preview.height
    var width = HERO_MAX_WIDTH
    var height = width / aspect
    if (height > HERO_MAX_HEIGHT) {
        height = HERO_MAX_HEIGHT
        width = height * aspect
    }
    return HeroFrame(maxOf(width, HERO_MIN_SIDE), maxOf(height, HERO_MIN_SIDE))
}
