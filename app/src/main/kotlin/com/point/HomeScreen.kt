package com.point

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Image
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.layout.ContentScale
import com.point.core.flow.META_ENTITY_PREFIX
import com.point.core.model.HistoryEntry
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.ui.Portal
import com.point.core.ui.PortalColumnWidth
import com.point.core.ui.PortalDoor
import com.point.core.ui.PortalRow
import com.point.core.ui.bubbleColor
import com.point.core.ui.bubbleIcon
import com.point.core.ui.kindIcon
import com.point.core.ui.kindLabel
import com.point.core.ui.theme.PointTheme
import com.point.core.ui.understoodFacts
import com.point.executors.Bitmaps
import androidx.compose.ui.tooling.preview.Preview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Point's home: the recent objects you brought in. Tap one to keep working with it —
 * no going back to the source app to share again (the metric: fewer switches).
 *
 * Дверей на этом экране ровно три, и у каждой есть имя (#462). Раньше в углу стояли три безымянные
 * иконки — стрелка вниз, монитор, шестерёнка, — и угадать по стрелке вниз «Принять файл» было
 * нельзя никак. Теперь это плиты дизайн-системы с подписями.
 *
 * Дверь «Новый объект» (#456) — та, которой не было вовсе: пять источников (камера, голос, буфер,
 * место, файл из чужих рук) жили за плиткой шторки, а плитку надо было самому найти в редакторе.
 * При этом один источник из пяти — «Принять файл» — успел получить собственную иконку здесь;
 * теперь он стоит среди своих, а не отдельно. Меню это не заводит: экран по-прежнему отвечает на
 * один вопрос, просто первый ответ на него — «объекта ещё нет, вот откуда его взять».
 */
@Composable
fun HomeScreen(
    recent: List<HistoryEntry>,
    onOpen: (HistoryEntry) -> Unit,
    onSettings: () -> Unit,
    onPc: () -> Unit = {},
    /** Дверь к выбору источника (#456): камера, голос, буфер, место, файл из чужих рук. */
    onNewObject: () -> Unit = {},
    /** Имена источников — подпись двери. Приходят от самих источников, здесь не переписаны. */
    sourceLabels: List<String> = emptyList(),
    onClear: () -> Unit = {},
    clipboard: String? = null,
    onUseClipboard: (String) -> Unit = {},
    onDismissClipboard: () -> Unit = {},
    crashReport: String? = null,
    onSendCrash: (String) -> Unit = {},
    onDismissCrash: () -> Unit = {},
    basketCount: Int = 0,
    onOpenBasket: () -> Unit = {},
    onClearBasket: () -> Unit = {},
    fromPcCount: Int = 0,
    onPullFromPc: () -> Unit = {},
    onHideFromPc: () -> Unit = {},
    /** Задан ли AI-ключ (#465). Пока нет — «Недавнее» зовёт его подключить и говорит зачем. */
    aiKeySet: Boolean = true,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().systemBarsPadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
        ) {
            // За дверью теперь круг устройств, а не один компьютер (#472), и подпись обязана
            // называть то, что там правда лежит: голая или врущая подпись — та же загадка, что в #462.
            PortalDoor(
                label = "Устройства",
                onClick = onPc,
                icon = bubbleIcon("pc"),
                accent = bubbleColor("pc"),
            )
            PortalDoor(
                label = "AI-ключ",
                onClick = onSettings,
                icon = bubbleIcon("ai"),
                accent = bubbleColor("ai"),
            )
        }

        // Зачем ключ — сказанное ДО того, как человек упёрся в отказ (#465). Свежепоставленный
        // Point молчал об этом вовсе: «Понять», «Перевести», «Спросить AI» и расшифровка отвечали
        // отказом, и узнавал человек о ключе в худший момент — когда действие уже провалилось.
        // Приглашение стоит здесь, а не на экране объекта: тот держит бюджет ≤300 мс без I/O.
        if (!aiKeySet) {
            Box(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                ConnectAiRow(onConnect = onSettings)
            }
        }

        if (crashReport != null) {
            // #11: crash visibility - offered once, leaves the device only by explicit share.
            CrashBanner(onSend = { onSendCrash(crashReport) }, onDismiss = onDismissCrash)
        }

        if (clipboard != null) {
            ClipboardBanner(clipboard, onUse = { onUseClipboard(clipboard) }, onDismiss = onDismissClipboard)
        }

        if (fromPcCount > 0) {
            FromPcBanner(fromPcCount, onPull = onPullFromPc, onHide = onHideFromPc)
        }

        if (basketCount > 0) {
            BasketBanner(basketCount, onOpen = onOpenBasket, onClear = onClearBasket)
        }

        if (recent.isEmpty()) {
            // Прокрутка — не украшение: у пустого дома теперь есть дверь под текстом, и на низком
            // экране (или с открытой плашкой буфера) она обязана оставаться достижимой.
            Box(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Portal(size = 168.dp) // the brand mark — the glowing point (redesign, экран 1)
                    Spacer(Modifier.height(28.dp))
                    Text(
                        "Point",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        // Раньше здесь был один путь внутрь — «поделитесь». Для того, у кого
                        // объекта ещё нет, это был тупик: сделать прямо тут было нечего (#456).
                        "Поделитесь объектом из любого приложения — или создайте его здесь",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(22.dp))
                    NewObjectDoor(sourceLabels = sourceLabels, onClick = onNewObject)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    NewObjectDoor(
                        sourceLabels = sourceLabels,
                        onClick = onNewObject,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                item {
                    Text(
                        "Недавнее",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                }
                items(recent, key = { it.id }) { entry ->
                    HistoryRow(entry = entry, onClick = { onOpen(entry) })
                }
                item {
                    TextButton(
                        onClick = onClear,
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    ) {
                        Text("Очистить недавнее", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/**
 * Дверь «Новый объект» (#456) — единственная светящаяся строка домашнего экрана.
 *
 * Ярко потому, что для человека без объекта это ЕДИНСТВЕННОЕ, что здесь можно сделать: «Недавнее»
 * пусто, делиться нечем. Подпись перечисляет источники поимённо — иначе четыре из пяти так и
 * остались бы догадкой, только на один тап ближе.
 */
@Composable
private fun NewObjectDoor(
    sourceLabels: List<String>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PortalRow(
        title = "Новый объект",
        subtitle = sourcesSubtitle(sourceLabels),
        onClick = onClick,
        icon = Icons.Filled.AddCircleOutline,
        primary = true,
        modifier = modifier.widthIn(max = PortalColumnWidth),
    )
}

/**
 * Из чего сегодня можно родить объект — подпись двери «Новый объект».
 *
 * Имена приходят от самих источников (`ObjectSource.label`), а не переписаны здесь руками: иначе
 * обещание «добавить источник = добавить класс» перестало бы работать ровно на том экране, где
 * человек о источниках впервые узнаёт. Пустой набор — законное состояние (`@Multibinds` в
 * `AppIconsModule`), и тогда подписи нет вовсе: врать про несуществующее нечем.
 */
internal fun sourcesSubtitle(labels: List<String>): String? =
    labels.filter { it.isNotBlank() }.takeIf { it.isNotEmpty() }?.joinToString(" · ")

/**
 * Приглашение подключить AI, пока ключа нет (#465).
 *
 * Не баннер и не «подсказка дня»: строка портала — тем же языком, каким Point предлагает действия
 * над объектом. Скрыть её нечем намеренно — это не новость, которую можно прочитать и забыть, а
 * состояние: половина Point молчит, пока ключа нет. Исчезнет она сама, когда ключ появится.
 *
 * Не светится, в отличие от «Нового объекта»: главное на этом экране — родить объект, а ключ это
 * то, без чего половина действий над ним промолчит. Две светящиеся строки рядом спорили бы за
 * внимание, и человек читал бы их обе как одинаково срочные.
 */
@Composable
private fun ConnectAiRow(onConnect: () -> Unit, modifier: Modifier = Modifier) {
    PortalRow(
        title = "Подключите AI — пара минут",
        subtitle = com.point.core.flow.AI_KEY_WHY_SHORT,
        onClick = onConnect,
        icon = com.point.core.ui.bubbleIcon("ai"),
        accent = com.point.core.ui.bubbleColor("ai"),
        subtitleMaxLines = 3,
        modifier = modifier.widthIn(max = PortalColumnWidth),
    )
}

/**
 * A dismissible suggestion when Point opens with actionable text in the clipboard (#72) — the
 * trigger that reaches messengers (copy in the app → open Point → act). Read foreground-only.
 */
/** Liquid pull (#161): the paired PC queued objects for this phone — one tap brings
 *  them here and opens the flow; the cross hides the offer without touching the queue. */
@Composable
private fun FromPcBanner(count: Int, onPull: () -> Unit, onHide: () -> Unit) {
    Surface(
        onClick = onPull,
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "С компьютера: $count",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    "Забрать и открыть здесь",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            IconButton(onClick = onHide) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Скрыть",
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}

/** The progressive object (#96): the pile keeps growing across flows; one tap opens
 *  it as a COLLECTION whose actions apply to everything together. */
@Composable
private fun BasketBanner(count: Int, onOpen: () -> Unit, onClear: () -> Unit) {
    Surface(
        onClick = onOpen,
        color = MaterialTheme.colorScheme.tertiaryContainer,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "Корзина: $count",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
                Text(
                    "Открыть всё вместе как один объект",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
            IconButton(onClick = onClear) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Очистить корзину",
                    tint = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
        }
    }
}

@Composable
private fun ClipboardBanner(text: String, onUse: () -> Unit, onDismiss: () -> Unit) {
    Surface(
        onClick = onUse,
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "Действие из буфера",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Text(
                    text,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Скрыть",
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
    }
}

/** The last-crash offer: one dismissible line, no automation whatsoever (#11). */
@Composable
private fun CrashBanner(onSend: () -> Unit, onDismiss: () -> Unit) {
    Surface(
        onClick = onSend,
        color = MaterialTheme.colorScheme.errorContainer,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "Point падал в прошлый раз",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Text(
                    "Нажмите, чтобы отправить отчёт разработчику - только по вашему решению.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Скрыть",
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
    }
}

@Composable
private fun HistoryRow(entry: HistoryEntry, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        androidx.compose.foundation.layout.Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            HistoryAvatar(entry)
            Column(Modifier.fillMaxWidth()) {
                Text(
                    text = entry.name ?: kindLabel(entry.kind), // #129: no raw MIME in a person's face
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // #114: a person remembers the object, not the clock — the kind leads,
                // the relative time only seconds it.
                Text(
                    text = kindLabel(entry.kind) + " · " +
                        DateUtils.getRelativeTimeSpanString(entry.epochMillis).toString(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // What Point understood back then («+380 67… · завтра 18:00») — the entry
                // is remembered by its content, not its filename (#114).
                val facts = entryFacts(entry)
                if (facts.isNotEmpty()) {
                    Text(
                        text = facts.take(2).joinToString(" · ") { it.value ?: it.label },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** The understood facts of a history entry — the same derivation the first screen uses,
 *  rebuilt from the persisted features + entity values (#114). */
private fun entryFacts(entry: HistoryEntry) = understoodFacts(
    PointObject(
        id = entry.id,
        mime = entry.mime,
        uri = entry.ref,
        state = ObjectState(entry.kind, entry.features),
        metadata = entry.entities.mapKeys { META_ENTITY_PREFIX + it.key },
    ),
)

private const val THUMB_PX = 96

/**
 * Row avatar: a real downsampled preview for images (loaded off-main, EXIF-upright),
 * falling back to the object-kind icon while it loads, for non-images, or on failure (#56).
 */
@Composable
private fun HistoryAvatar(entry: HistoryEntry) {
    val isImage = entry.kind == ObjectKind.IMAGE || entry.mime.startsWith("image/")
    var thumb by remember(entry.id) { mutableStateOf<ImageBitmap?>(null) }
    if (isImage) {
        LaunchedEffect(entry.id) {
            thumb = withContext(Dispatchers.IO) {
                runCatching { Bitmaps.decodeThumbnail(entry.ref.value, THUMB_PX)?.asImageBitmap() }.getOrNull()
            }
        }
    }
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.size(44.dp)) {
        val bmp = thumb
        if (bmp != null) {
            Image(
                bitmap = bmp,
                contentDescription = entry.name ?: entry.kind.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                imageVector = kindIcon(entry.kind),
                contentDescription = entry.kind.name,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.padding(10.dp).fillMaxSize(),
            )
        }
    }
}

/** Источники так, как их видит домашний экран: только имена. */
private val PREVIEW_SOURCES =
    listOf("Буфер обмена", "Голос", "Камера", "Место", "Принять файл")

private fun previewEntry(id: String, name: String, kind: ObjectKind, ago: Long) = HistoryEntry(
    id = id,
    name = name,
    mime = "text/plain",
    ref = com.point.core.model.ScratchRef("scratch/$id"),
    kind = kind,
    epochMillis = System.currentTimeMillis() - ago,
)

// Пустой дом — то состояние, в котором человек оказывается первым: объекта ещё нет. Именно здесь
// «четыре источника из пяти спрятаны» (#456) видно глазами: без двери экран не предлагал ничего.
@Preview(name = "Дом · объекта ещё нет (#456)", showBackground = true, backgroundColor = 0xFF0B0D10)
@Composable
private fun PreviewHomeEmpty() = PointTheme {
    HomeScreen(recent = emptyList(), onOpen = {}, onSettings = {}, sourceLabels = PREVIEW_SOURCES)
}

// Дом с работой: подписанные двери в углу (#462) и та же дверь «Новый объект» над «Недавним».
@Preview(name = "Дом · недавнее (#462)", showBackground = true, backgroundColor = 0xFF0B0D10)
@Composable
private fun PreviewHomeRecent() = PointTheme {
    HomeScreen(
        recent = listOf(
            previewEntry("1", "Счёт за свет.pdf", ObjectKind.PDF, 3 * 60 * 1000L),
            previewEntry("2", "Расписка", ObjectKind.TEXT, 40 * 60 * 1000L),
        ),
        onOpen = {},
        onSettings = {},
        sourceLabels = PREVIEW_SOURCES,
    )
}
