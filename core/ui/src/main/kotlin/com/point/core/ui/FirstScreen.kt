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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.point.core.flow.META_ALT_SUFFIX
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
import com.point.core.model.FavoriteChain
import com.point.core.model.PointObject
import com.point.core.model.RelationType
import com.point.core.model.Relation

/**
 * The first (and every) screen: a preview of the current object and the bubbles
 * that accept its state. Pure and stateless — driven entirely by its arguments,
 * so it renders fully in `@Preview` with no device or APK. See FirstScreenPreview.
 *
 * When [inputPrompt] is non-null an executor is awaiting free-text input; the
 * bubbles are replaced by a text field. [message] shows a transient result
 * (a Failure reason or a Done confirmation). Bubbles fade/scale in with a stagger
 * — so newly disclosed bubbles (progressive disclosure) visibly appear.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FirstScreen(
    obj: PointObject,
    bubbles: List<Bubble>,
    onBubble: (Bubble) -> Unit,
    modifier: Modifier = Modifier,
    message: String? = null,
    /** Чем это кончилось: знак и свет исхода — второе сообщение после текста, и врать им нельзя.
     *  Умолчание — [Outcome.NONE]: забытый исход молчит, а не отчитывается об успехе. */
    messageOutcome: Outcome = Outcome.NONE,
    inputPrompt: String? = null,
    inputSuggestions: List<String> = emptyList(),
    onSubmitInput: (String) -> Unit = {},
    onCancelInput: () -> Unit = {},
    favorites: List<FavoriteChain> = emptyList(),
    onApplyFavorite: (FavoriteChain) -> Unit = {},
    canSaveChain: Boolean = false,
    onSaveChain: () -> Unit = {},
    items: List<PointObject> = emptyList(),
    /** Сколько файлов в наборе всего — [items] может быть обрезан пределом обхода (#460).
     *  0 — счёта нет, и экран не выдумывает его из длины списка. */
    itemsTotal: Int = 0,
    /** [itemsTotal] — «не меньше чем»: обход упёрся в свой потолок и перестал считать. */
    itemsTotalAtLeast: Boolean = false,
    onItem: (PointObject) -> Unit = {},
    found: List<PointObject> = emptyList(),
    relations: List<Relation> = emptyList(),
    onFound: (PointObject) -> Unit = {},
    textPreview: String? = null,
    latent: List<LatentBubble> = emptyList(),
    enriching: List<String> = emptyList(),
    discover: Bubble? = null,
    working: Boolean = false,
    /** Что идущее действие говорит о себе (#288) — та же строка, что показал бы экран ожидания.
     *  null — действие молчит, и экран за него не сочиняет. */
    workingStage: String? = null,
    previewBitmap: ImageBitmap? = null,
    pinned: CapabilityId? = null,
    onBubbleLongPress: (Bubble) -> Unit = {},
    appIconFor: (String) -> ImageBitmap? = { null },
    /** #259: тап по герою открывает выделение; null — у объекта нет слоя слов, тап не предлагается. */
    onHeroTap: (() -> Unit)? = null,
) {
    // Прокрутка держится в переменной, потому что исход действия обязан оказаться на глазах:
    // список действий длиннее экрана, и человек, тапнувший внизу, остался бы внизу (см. ниже).
    val scroll = rememberScrollState()
    Column(
        modifier = modifier
            .fillMaxSize()
            // The object screen is a scan-down list (design system, docs/design-system.png):
            // object → understood → the action sections. It scrolls when actions outgrow the view.
            .verticalScroll(scroll)
            .padding(horizontal = 20.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val facts = understoodFacts(obj)
        // A fact that has become a thing (#222) is shown as that thing, not twice: «Нашёл
        // адрес · Отделение №9» graduates out of the checklist into the object list below.
        val promoted = found.mapNotNullTo(mutableSetOf()) { factKeyFor(it.state.kind) }
        val plainFacts = facts.filter { it.key !in promoted }

        // The object is the hero (#114): its real preview breathes inside the portal aura.
        ObjectHeader(
            obj,
            thinking = enriching.isNotEmpty() || working,
            factCount = facts.size,
            preview = previewBitmap,
            onTap = onHeroTap,
        )

        // Что действие делает СЕЙЧАС — там же, где объект «работает» (#288). Быстрые действия
        // идут без экрана ожидания (M3: он мигал бы на каждом мелком тапе), и до сих пор их
        // слова было негде показать: «Скан», «В Word», «Страницы», «Распаковать» на большом
        // файле работали секунды притушенным списком и без единой строки.
        WorkingStage(workingStage)

        // Исход только что сделанного — СРАЗУ под объектом, а не в конце списка.
        //
        // Живая приёмка 03.08.2026: «Прочитать показание» (действие убрано в #396) честно
        // возвращало отказ, а человек не видел ничего — баннер стоял последним элементом
        // прокручиваемого экрана, ниже всех действий, подсказок и цепочек. Ответ существовал
        // ровно там, куда никто не смотрит; снаружи это неотличимо от «действие ничего не
        // сделало» — и это же ощущение владелец описал в #288 словом «зависло».
        //
        // Как он выглядит — OutcomeBanner: карточка портала, где исход различают знак и его свет.
        OutcomeBanner(message, messageOutcome)

        // Ссылка, которую Point только что выдал, — сразу и кодом (#388). Человек рядом наводит
        // камеру и забирает файл, ничего никому не пересылая; тут же сказано, чем за это платят.
        issuedLinkOf(obj.metadata)?.let { link ->
            Spacer(Modifier.height(16.dp))
            LinkCard(url = link, title = "Ссылка на файл", warning = issuedLinkWarning(obj.metadata))
        }

        // «Point понял» (#114): the understanding card — facts land line by line as
        // enrichment delivers them (#64), with still-running work inside the same card.
        UnderstoodSection(facts = plainFacts, enriching = enriching)

        // Готовность действий (#260): полнота считается по действию — «не хватает только X»,
        // а не форма из девяти полей. Схемы читают те же факты, что пишут энричеры и «Понять».
        ReadinessSection(metadata = obj.metadata)

        // What Point found INSIDE the object (#222) — things, not lines: the waybill number,
        // the branch, the deadline. Each opens as an object of its own.
        if (found.isNotEmpty() && inputPrompt == null) {
            FoundObjects(found = found, relations = relations, onFound = onFound)
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
            TextPreview(text = textPreview, markdown = obj.mime == "text/markdown")
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
            // Actions grouped by intent (Variant C) as design-system rows — dense and calm,
            // in place of the old floating bubbles the owner found chaotic.
            ObjectActions(
                sections = actionSections(bubbles),
                working = working,
                pinned = pinned,
                onBubble = onBubble,
                onBubbleLongPress = onBubbleLongPress,
                appIconFor = appIconFor,
            )
            if (latent.isNotEmpty()) {
                Spacer(Modifier.height(22.dp))
                LatentHints(latent)
            }
        }

        if (inputPrompt == null && (favorites.isNotEmpty() || canSaveChain)) {
            Spacer(Modifier.height(20.dp))
            ChainSection(favorites, onApplyFavorite, canSaveChain, onSaveChain)
        }

    }

    // Человек мог тапнуть по действию в самом низу длинного списка — и остаться внизу. Сообщение
    // под объектом ему тогда не поможет, поэтому экран сам возвращается к нему: исход показывают,
    // а не прячут за жестом.
    LaunchedEffect(message) { if (message != null) scroll.animateScrollTo(0) }
}

/**
 * "Почти доступно" (#97 negotiation): capabilities one signal away, dimmed, each with what it still
 * needs. Informational — it teaches the object's latent powers without crowding the real actions.
 */
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChainSection(
    favorites: List<FavoriteChain>,
    onApply: (FavoriteChain) -> Unit,
    canSave: Boolean,
    onSave: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (favorites.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
                favorites.forEach { chain ->
                    Surface(
                        onClick = { onApply(chain) },
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        tonalElevation = 1.dp,
                    ) {
                        Text(
                            text = "▸ ${chain.name}",
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        if (canSave) {
            TextButton(onClick = onSave) { Text("★ Сохранить цепочку") }
        }
    }
}

/**
 * Голос тихой работы (#288): одна строка под объектом — что действие делает прямо сейчас.
 *
 * Тем же языком, что «Point думает» об обогащении ([ThinkingDot] + подпись): человеку всё равно,
 * кто внутри Point занят — объект работает, и работа названа. Пульс здесь не украшение, а
 * доказательство хода: строка «Распаковываю архив» без него замирает так же немо, как замирал
 * притушенный список.
 *
 * Появляется строго по факту первой стадии. Молчащее действие ничего не рисует: короткие
 * («Копировать», «Поделиться», QR) стадий не сообщают вовсе — мигание на каждом мелком тапе
 * было бы шумом, а не информацией.
 */
@Composable
private fun WorkingStage(stage: String?) {
    // Последнее сказанное держится, пока строка уезжает, — иначе она гасла бы пустой.
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

/** For a COLLECTION: a scrollable list of its items. Tapping one drills in — the
 *  normal flow continues on that object (its own bubbles: Открыть/Сохранить/…). */
/**
 * «Point нашёл» (#222) — the things extraction lifted out of the object, each one tappable.
 *
 * The list is the visible half of the migration: an address here is not a line of text but an
 * `Address` object, and tapping it opens a screen whose actions come from the same registry
 * every other object uses. «Маршрут» appears there because the object carries `HAS_ADDRESS`,
 * not because anyone wrote a case for it.
 *
 * Под значением — **откуда оно взялось** (#264): «прочитано» / «выведено правилом» / «прочитано
 * моделью» / «подтверждено вами». Раньше здесь стояло одно слово «возможно» на любое
 * `confidence < 1f`, и трек, найденный правилом дословно на странице (0.8), выглядел ровно так
 * же, как имя перевозчика, которое модель назвала сама (0.7). «Возможно» осталось, но теперь
 * оно означает ровно две вещи и **вычисляется** ([isDoubtful]): улик меньше двух независимых
 * классов либо источники спорят о чтении.
 */
@Composable
private fun FoundObjects(
    found: List<PointObject>,
    relations: List<Relation>,
    onFound: (PointObject) -> Unit,
) {
    Spacer(Modifier.height(16.dp))
    Column(
        modifier = Modifier.widthIn(max = 340.dp).fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "Нашёл · ${found.size}",
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
                                text = obj.uri.value,
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
                                    // Происхождение — честная подпись вместо суррогатного числа.
                                    provenanceLabel(obj.provenance),
                                    // «Возможно» — только предположение или спор, не «не 1.0».
                                    "возможно".takeIf { isDoubtful(obj.metadata) },
                                ).joinToString(" · "),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            // #222, шаг 7: sources read it differently — say so, do not pick
                            // one silently. A value nobody agrees on is not a settled value.
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
 * The role a found object plays in the document (#222, шаг 6) — the answer the classifier was
 * actually asked for. Provenance («found_in») is not a role: it says where the thing was read,
 * which the screen already makes obvious by showing it under this object.
 */
/** The reading this object's sources disagreed with, if any — its own value is the winner
 *  of the vote, and the loser is worth one quiet line rather than silence. */
private fun otherReading(obj: PointObject): String? =
    obj.metadata.keys.firstOrNull { it.endsWith(META_ALT_SUFFIX) }
        ?.let { alternativesOf(obj.metadata, it.removeSuffix(META_ALT_SUFFIX)) }
        ?.firstOrNull { it.trim() != obj.uri.value.trim() }

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

/** Which «Point понял» line a found object replaces — null when nothing was showing it. */
internal fun factKeyFor(kind: ObjectKind): String? = when (kind) {
    KIND_PHONE -> "phone"
    KIND_EMAIL -> "email"
    KIND_URL -> "url"
    KIND_ADDRESS -> "address"
    KIND_DATE -> "date"
    else -> null // an Identifier was never in the checklist — it had no type to be shown as
}

/** Сколько строк набора рисуется за раз; остальное — по тапу «Показать ещё» (#460). */
const val COLLECTION_PAGE = 25

/**
 * Заголовок содержимого: сколько показано и сколько там на самом деле (#460).
 *
 * Обрезанный список обязан называть себя обрезанным: снаружи «Содержимое · 500» неотличимо от
 * набора ровно из пятисот файлов, и человек уверен, что видел всё. [atLeast] — обход упёрся в
 * потолок и перестал считать, то есть и само число «не меньше чем».
 */
fun collectionLabel(shown: Int, total: Int, atLeast: Boolean): String = when {
    total <= shown && !atLeast -> "Содержимое · ${grouped(shown)}"
    total <= shown -> "Содержимое · ${grouped(shown)}, и это не всё"
    atLeast -> "Содержимое · ${grouped(shown)} из более чем ${grouped(total)}"
    else -> "Содержимое · ${grouped(shown)} из ${grouped(total)}"
}

/** Число человеку: разряды отбиты неразрывным пробелом («1 340», а не «1340»). */
internal fun grouped(n: Int): String =
    n.toString().reversed().chunked(3).joinToString(" ").reversed()

@Composable
private fun CollectionItems(
    items: List<PointObject>,
    total: Int,
    atLeast: Boolean,
    onItem: (PointObject) -> Unit,
) {
    // Строки рисуются страницами, и своей прокрутки у списка больше нет (#460). Прокручиваемая
    // область внутри прокручиваемого экрана забирала жест себе: палец на списке двигал список, а
    // страницу человек прокрутить не мог вовсе. Страница же держит и второе обещание — действия
    // объекта остаются в досягаемости, а не уезжают на тысячу строк вниз.
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

/** Сколько знаков текста видно сразу; остальное — по тапу «Показать целиком» (#460). */
const val TEXT_PREVIEW_HEAD = 2_000

/**
 * Начало длинного текста — по границе строки, чтобы разметка не рвалась посередине слова.
 *
 * Обрыв ищется во второй половине куска: у текста без переносов (одна строка на сто тысяч знаков)
 * границы нет вовсе, и резать по началу было бы хуже, чем резать ровно по пределу.
 */
fun textPreviewHead(text: String, limit: Int = TEXT_PREVIEW_HEAD): String {
    if (text.length <= limit) return text
    val head = text.take(limit)
    val cut = head.lastIndexOf('\n')
    return if (cut > limit / 2) head.substring(0, cut) else head
}

/** For a TEXT object: its content, readable in-app — native select/copy. */
@Composable
private fun TextPreview(text: String, markdown: Boolean = false) {
    // Своей прокрутки у панели больше нет (#460): прокручиваемый текст внутри прокручиваемого
    // экрана забирал жест себе. Видно начало, целиком — по явному тапу: сто тысяч знаков,
    // развёрнутые молча, увели бы действия объекта на десятки экранов вниз.
    var expanded by rememberSaveable(text.length) { mutableStateOf(false) }
    val head = remember(text) { textPreviewHead(text) }
    val shown = if (expanded) text else head
    // AI answers arrive as Markdown — render headings/bold/bullets instead of raw `###`/`**`/`*`.
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
            )
        }
    }
    if (head.length < text.length) {
        TextButton(onClick = { expanded = !expanded }) {
            Text(
                if (expanded) "Свернуть"
                else "Показать целиком · ещё ${grouped(text.length - head.length)} символов",
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
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        // M1 (MOTION.md): the object breathes with its kind's physics; a light ring
        // pulses while enrichment thinks; the shadow warms into an aura once understood.
        // The hero is the object itself (#114): a real thumbnail when we have one,
        // the kind icon only as the first frame / non-visual fallback.
        val headerSize = if (preview != null) 132.dp else 96.dp
        // Concept borrow ("объект внутри портала"): the object sits inside the brand portal ring —
        // the same neon motif as Home and the busy screen. The halo brightens as understanding grows
        // ("Point понял" = the glow rises) and while enrichment is thinking. The AliveSurface below
        // keeps the object's breath / reading-beat; the portal is the frame around it.
        Box(
            contentAlignment = Alignment.Center,
            // #259: сам объект и есть кнопка «рассмотреть ближе» — без ряби, портал не мигает.
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
                shape = RoundedCornerShape(26.dp),
                size = headerSize,
            ) {
                if (preview != null) {
                    Image(
                        bitmap = preview,
                        contentDescription = obj.metadata["name"] ?: obj.state.kind.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(headerSize)
                            .clip(RoundedCornerShape(26.dp)),
                    )
                } else if (objectMark(obj) == ObjectMark.SPREADSHEET) {
                    // #295: результат «В Excel» больше не выглядит как любой другой документ —
                    // у таблицы свой знак, рождающийся в языке портала.
                    SpreadsheetMark(size = headerSize)
                } else {
                    Surface(
                        shape = RoundedCornerShape(26.dp),
                        color = MaterialTheme.colorScheme.secondary,
                        shadowElevation = 0.dp,
                        modifier = Modifier.size(headerSize),
                    ) {
                        Icon(
                            imageVector = kindIcon(obj.state.kind),
                            contentDescription = obj.state.kind.name,
                            tint = MaterialTheme.colorScheme.onSecondary,
                            modifier = Modifier
                                .padding(26.dp)
                                .fillMaxSize(),
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        // #114: the hero says WHAT it is — the verdict (semantic type once understood, else the
        // kind), with the human summary / file name beneath, never a raw MIME (#129).
        val verdict = objectVerdict(obj)
        Text(
            text = verdict.headline,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        verdict.subline?.let { sub ->
            Text(
                text = sub,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** #114: how many top-ranked actions are shown big. With ≤2 more than that, folding
 *  is sillier than showing — everything is "likely". Shared with the ViewModel so the
 *  Discover hint knows exactly which actions the user does NOT see. */
const val LIKELY_COUNT = 3

/** The number of actions actually shown big for [total] candidates (see [LIKELY_COUNT]). */
fun likelyCount(total: Int): Int = if (total <= LIKELY_COUNT + 2) total else LIKELY_COUNT

/**
 * The object screen's action sections (design system, docs/design-system.png): actions are rows
 * grouped by the user [Intent] they serve — Извлечь (understand/extract), Превратить (make a new
 * artifact), Отправить (send/open out). This is the "Variant C" the owner picked: navigation by goal.
 */
enum class ActionGroup(val label: String) {
    EXTRACT("Извлечь"),
    TRANSFORM("Превратить"),
    SEND("Отправить"),
}

/** Which section an [intent] belongs to; OPEN and SEND share the outward-facing «Отправить» group. */
fun actionGroupOf(intent: Intent): ActionGroup = when (intent) {
    Intent.UNDERSTAND -> ActionGroup.EXTRACT
    Intent.PREPARE -> ActionGroup.TRANSFORM
    Intent.OPEN, Intent.SEND -> ActionGroup.SEND
}

data class ActionSection(val group: ActionGroup, val bubbles: List<Bubble>)

/** Group ranked [bubbles] into intent sections, keeping the BubblePolicy order within each group and
 *  dropping empty groups; sections come in Извлечь→Превратить→Отправить order. Pure — JVM-tested. */
fun actionSections(bubbles: List<Bubble>): List<ActionSection> =
    ActionGroup.entries.mapNotNull { group ->
        bubbles.filter { actionGroupOf(it.intent) == group }
            .takeIf { it.isNotEmpty() }
            ?.let { ActionSection(group, it) }
    }

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun AmendmentInput(
    prompt: String,
    onSubmit: (String) -> Unit,
    onCancel: () -> Unit,
    suggestions: List<String> = emptyList(),
) {
    // `rememberSaveable` (#114): дописанный запрос переживает поворот телефона.
    var text by rememberSaveable { mutableStateOf("") }
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
            // The 3 most-likely prompts (#86): tap one to run it instead of typing.
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
            modifier = Modifier.fillMaxWidth(),
            singleLine = false,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onCancel) { Text("Отмена") }
            Button(onClick = { onSubmit(text) }) { Text("Готово") }
        }
    }
}
