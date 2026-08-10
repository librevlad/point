package com.point

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.layout.ContentScale
import com.point.core.flow.SETTINGS_TITLE
import com.point.core.flow.agoLabel
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
import com.point.core.ui.portalCard
import com.point.core.ui.theme.PointTheme
import com.point.core.ui.objectVerdict
import com.point.core.ui.understoodFacts
import com.point.executors.Bitmaps
import androidx.compose.ui.tooling.preview.Preview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

internal const val WHAT_POINT_IS: String =
    "Дайте фото, скриншот, документ или текст — Point прочитает его и покажет, что с ним можно " +
        "сделать"

internal const val HOW_TO_SHARE: String =
    "Или нажмите «Поделиться» в любом приложении и выберите Point"

internal const val EXAMPLE_DOOR_WHAT: String =
    "Снимок визитки лежит в самом Point. Откроется как обычный объект — без ключа, без сети и без " +
        "разрешений."

internal const val REMOVE_ENTRY: String = "Убрать"

internal const val ENTRY_MENU: String = "Что можно с записью"

internal const val CLEAR_RECENT: String = "Очистить недавнее"

internal const val CLEAR_RECENT_ASK: String = "Убрать всё недавнее?"

internal const val CLEAR_RECENT_WHAT: String =
    "Уйдут все записи и всё, что Point о них узнал. Одну запись можно убрать через её меню."

internal const val CLEAR_RECENT_CONFIRM: String = "Убрать всё"

internal const val CANCEL: String = "Отмена"

/** Дверь входа спрашивает человека, а не перечисляет наши источники (макет владельца 10.08.2026). */
internal const val WHAT_YOU_HAVE: String = "Что у вас есть?"

internal const val RECENT_TITLE: String = "Недавнее"

internal const val RECENT_WHAT: String = "Последние объекты"

@Composable
fun HomeScreen(
    recent: List<HistoryEntry>,
    onOpen: (HistoryEntry) -> Unit,
    onSettings: () -> Unit,

    onNewObject: () -> Unit = {},

    onExample: () -> Unit = {},

    sourceLabels: List<String> = emptyList(),
    onRemove: (HistoryEntry) -> Unit = {},
    onClear: () -> Unit = {},
    crashReport: String? = null,
    onSendCrash: (String) -> Unit = {},
    onDismissCrash: () -> Unit = {},
    fromPcCount: Int = 0,
    onPullFromPc: () -> Unit = {},
    onHideFromPc: () -> Unit = {},

    aiKeySet: Boolean = true,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().systemBarsPadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
        ) {

            PortalDoor(
                label = SETTINGS_TITLE,
                onClick = onSettings,
                icon = bubbleIcon("settings"),
                accent = bubbleColor("settings"),
            )
        }

        if (crashReport != null) {

            CrashBanner(onSend = { onSendCrash(crashReport) }, onDismiss = onDismissCrash)
        }

        if (fromPcCount > 0) {
            FromPcBanner(fromPcCount, onPull = onPullFromPc, onHide = onHideFromPc)
        }

        if (recent.isEmpty()) {

            Box(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Portal(size = 168.dp)
                    Spacer(Modifier.height(28.dp))
                    Text(
                        "Point",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(

                        WHAT_POINT_IS,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(22.dp))
                    NewObjectDoor(sourceLabels = sourceLabels, onClick = onNewObject)
                    Spacer(Modifier.height(9.dp))
                    Text(
                        HOW_TO_SHARE,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Spacer(Modifier.height(11.dp))
                    ExampleDoor(onClick = onExample)
                    if (!aiKeySet) {
                        Spacer(Modifier.height(14.dp))
                        ConnectAiRow(onConnect = onSettings)
                    }
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

                        // Вровень с записями и карточкой компьютера: узкая колонка (340.dp)
                        // хороша на пустом экране по центру, но в списке дверь входа
                        // не дотягивала до края и выглядела случайно уже соседей.
                        wide = true,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                if (!aiKeySet) {

                    item { ConnectAiRow(onConnect = onSettings, modifier = Modifier.padding(bottom = 8.dp)) }
                }
                item {
                    Column(modifier = Modifier.padding(bottom = 6.dp)) {
                        Text(RECENT_TITLE, style = MaterialTheme.typography.titleLarge)
                        Text(
                            RECENT_WHAT,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                items(recent, key = { it.id }) { entry ->
                    RemovableHistoryRow(
                        entry = entry,
                        onClick = { onOpen(entry) },
                        onRemove = { onRemove(entry) },
                    )
                }
                item {
                    var asking by rememberSaveable { mutableStateOf(false) }
                    if (asking) {
                        ClearRecentPanel(
                            onConfirm = { asking = false; onClear() },
                            onCancel = { asking = false },
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    } else {
                        TextButton(
                            onClick = { asking = true },
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                        ) {
                            Text(CLEAR_RECENT, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NewObjectDoor(
    sourceLabels: List<String>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    wide: Boolean = false,
) {
    PortalRow(
        title = "Новый объект",

        // Дверь спрашивает человека, а не перечисляет наши источники: «Буфер обмена ·
        // Звукозапись · Камера · Место · Принять файл» — это наш список, а не его вопрос
        // (макет владельца 10.08.2026, «чуть чище главное окно»). Источники он увидит,
        // как только войдёт.
        subtitle = WHAT_YOU_HAVE,
        onClick = onClick,
        icon = Icons.Filled.AddCircleOutline,
        primary = true,
        modifier = if (wide) modifier else modifier.widthIn(max = PortalColumnWidth),
    )
}

@Composable
private fun ExampleDoor(onClick: () -> Unit, modifier: Modifier = Modifier) {
    PortalRow(
        title = "Посмотреть на примере",
        subtitle = EXAMPLE_DOOR_WHAT,
        onClick = onClick,

        icon = bubbleIcon("ocr"),
        accent = bubbleColor("ocr"),
        modifier = modifier.widthIn(max = PortalColumnWidth),
    )
}

internal fun sourcesSubtitle(labels: List<String>): String? =
    labels.filter { it.isNotBlank() }.takeIf { it.isNotEmpty() }?.joinToString(" · ")

@Composable
private fun ConnectAiRow(onConnect: () -> Unit, modifier: Modifier = Modifier) {
    PortalRow(

        title = "Подключить AI",
        subtitle = com.point.core.flow.AI_KEY_WHY_SHORT,
        onClick = onConnect,
        icon = com.point.core.ui.bubbleIcon("ai"),
        accent = com.point.core.ui.bubbleColor("ai"),
        subtitleMaxLines = 3,
        modifier = modifier.widthIn(max = PortalColumnWidth),
    )
}

/**
 * Ждущее на компьютере — обычная карточка над входом, а не цветная плашка-объявление
 * (макет владельца 10.08.2026). Компьютер здесь такое же место, откуда приходит объект,
 * как камера или буфер: кричать об этом незачем.
 */
@Composable
private fun FromPcBanner(count: Int, onPull: () -> Unit, onHide: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PortalRow(
            title = "Компьютер · $count",
            subtitle = "Забрать и открыть здесь",
            onClick = onPull,
            icon = bubbleIcon("pc"),
            accent = bubbleColor("pc"),
            chevron = false,
            modifier = Modifier.weight(1f),
            trailing = {
                IconButton(onClick = onHide) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "Скрыть",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
        )
    }
}


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
                    "Нажмите, чтобы отправить отчёт разработчику.",
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

/**
 * Вопрос задаётся на месте, панелью в списке (#543, решение владельца): диалогов в Point нет ни
 * одного, а промах пальцем по «Очистить недавнее» стирал всю работу без единого вопроса.
 */
@Composable
private fun ClearRecentPanel(
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().portalCard().padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(CLEAR_RECENT_ASK, style = MaterialTheme.typography.titleMedium)
        Text(
            CLEAR_RECENT_WHAT,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        ) {
            TextButton(onClick = onCancel) {
                Text(CANCEL, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = onConfirm) {
                Text(CLEAR_RECENT_CONFIRM, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

/** Ширина открывающегося «Убрать» — она же порог, после которого строка остаётся открытой. */
/**
 * Меню у записи (решение владельца 10.08.2026: «делаем меню вместо свайпа»).
 *
 * Свайп прожил полдня и ушёл: жест невидим — человек не знает, что запись вообще можно убрать, —
 * и спорит с прокруткой списка под пальцем. Три точки видно всегда, и они честно говорят, что у
 * записи есть свои действия.
 */
@Composable
private fun RemovableHistoryRow(entry: HistoryEntry, onClick: () -> Unit, onRemove: () -> Unit) {
    var open by rememberSaveable(entry.id) { mutableStateOf(false) }
    HistoryRow(
        entry = entry,
        onClick = onClick,
        trailing = {

            // Меню растёт от самих трёх точек, а не от края строки: иначе оно вылезает
            // слева и накрывает соседнюю запись (видно в живом прогоне 10.08.2026).
            Box {
                IconButton(onClick = { open = true }) {
                    Icon(
                        imageVector = Icons.Filled.MoreVert,
                        contentDescription = ENTRY_MENU,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    DropdownMenuItem(
                        text = { Text(REMOVE_ENTRY, color = MaterialTheme.colorScheme.error) },
                        onClick = { open = false; onRemove() },
                    )
                }
            }
        },
    )
}

@Composable
private fun HistoryRow(
    entry: HistoryEntry,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 1.dp,
        modifier = modifier.fillMaxWidth(),
    ) {
        androidx.compose.foundation.layout.Row(
            modifier = Modifier.padding(start = 14.dp, top = 14.dp, bottom = 14.dp, end = if (trailing == null) 14.dp else 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            HistoryAvatar(entry)
            Column(Modifier.weight(1f)) {
                Text(
                    text = entryTitle(entry),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                Text(
                    text = historySubtitle(
                        name = entry.name.takeIf { entryTitle(entry) != it },
                        kind = kindLabel(entry.kind),

                        ago = agoLabel(System.currentTimeMillis() - entry.epochMillis),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

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
            trailing?.invoke()
        }
    }
}

/**
 * Подзаголовок досказывает то, чего нет в заголовке: имя файла — когда заголовок назвал смысл,
 * вид объекта — когда заголовком стало имя. Одно и то же дважды не печатается.
 */
internal fun historySubtitle(name: String?, kind: String, ago: String): String = when {
    name == null -> "$kind · $ago"
    name.startsWith(kind, ignoreCase = true) -> ago
    else -> "$name · $ago"
}

private fun entryFacts(entry: HistoryEntry) = understoodFacts(entryObject(entry))

/**
 * Запись «Недавнего» — тот же объект со всем знанием (#687): в истории лежит его метадата
 * целиком, а не одни сущности. Значит и назвать его можно так же, как на экране объекта.
 */
private fun entryObject(entry: HistoryEntry) = PointObject(
    id = entry.id,
    mime = entry.mime,
    uri = entry.ref,
    state = ObjectState(entry.kind, entry.features),
    metadata = entry.metadata,
)

/**
 * Заголовок карточки — смысл, а не файл (#639): «Покупка», «Визитка», «Договор» вместо
 * «probe.jpg». Имя файла уходит подзаголовком: оно всё ещё нужно, чтобы узнать свою вещь,
 * но человек ищет глазами не его.
 *
 * Тем же словарём, что на экране объекта (`objectVerdict`), — второго языка для тех же
 * вещей не заводим. Старые записи, у которых знания нет, называются как раньше.
 */
internal fun entryTitle(entry: HistoryEntry): String {
    val verdict = objectVerdict(entryObject(entry))
    val named = entry.name?.takeIf { it.isNotBlank() }
    val meaningful = verdict.headline.takeIf { it != kindLabel(entry.kind) }
    return meaningful ?: named ?: kindLabel(entry.kind)
}

private const val THUMB_PX = 96

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

@Preview(name = "Дом · объекта ещё нет (#456)", showBackground = true, backgroundColor = 0xFF0B0D10)
@Composable
private fun PreviewHomeEmpty() = PointTheme {
    HomeScreen(recent = emptyList(), onOpen = {}, onSettings = {}, sourceLabels = PREVIEW_SOURCES)
}

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
