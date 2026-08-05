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
import com.point.core.ui.theme.PointTheme
import com.point.core.ui.understoodFacts
import com.point.executors.Bitmaps
import androidx.compose.ui.tooling.preview.Preview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Что такое Point — одной строкой, на пустом доме, до всякой настройки.
 *
 * Продукт нигде не говорил, что он такое: слово «объект» — внутреннее, а первым сообщением стоял
 * призыв подключить чужой AI-сервис. Новый человек начинал знакомство с рассказа о том, чего
 * Point без ключа не умеет, — при том что лучшее, что он умеет, работает бесплатно и без сети.
 *
 * Строка называет продукт глаголом и примером, а не определением: «дайте — прочитает — подскажет».
 * Тур, экскурсия и обучающие экраны для этого не нужны и заведены не будут.
 */
internal const val WHAT_POINT_IS: String =
    "Дайте фото, скриншот, документ или текст — Point прочитает его и покажет, что с ним можно " +
        "сделать"

/**
 * Как объект попадает в Point — главным путём, названным словом (#580).
 *
 * «Поделиться» — вход, которым нельзя воспользоваться изнутри приложения: он живёт в чужих
 * приложениях, в системном меню. Поэтому дверь «Новый объект» его и не перечисляет — а человек
 * без этой строки не догадывается, что Point вообще так открывается. Двое из шести моделей
 * назвали это первым, чего не хватает новому человеку.
 */
internal const val HOW_TO_SHARE: String =
    "Или нажмите «Поделиться» в любом приложении и выберите Point"

/**
 * Что обещает строка «Посмотреть на примере» (#210).
 *
 * Обещано ровно то, что Point выполнит у человека, поставившего его минуту назад: снимок лежит в
 * самом приложении, читается на телефоне, и за это ни у кого ничего не спрашивают. Ни слова про
 * «демо», «тур» и «шаг 1 из 3»: за строкой не режим, а обычный объект.
 */
internal const val EXAMPLE_DOOR_WHAT: String =
    "Снимок визитки лежит в самом Point. Откроется как обычный объект — без ключа, без сети и без " +
        "разрешений."

/**
 * Point's home: the recent objects you brought in. Tap one to keep working with it —
 * no going back to the source app to share again (the metric: fewer switches).
 *
 * Дверей на этом экране ровно две, и у каждой есть имя (#462). Раньше в углу стояли три безымянные
 * иконки — стрелка вниз, монитор, шестерёнка, — и угадать по стрелке вниз «Принять файл» было
 * нельзя никак. Теперь это плиты дизайн-системы с подписями.
 *
 * Служебная дверь одна, и называется она «Настройки» (#544). Их было две — «Устройства» и
 * «AI-ключ», — и обе врали в меньшую сторону: за первой лежал ещё и аккаунт со входом, за второй —
 * пять настроек вместо одной, так что человек, которому надо выключить звук или запретить облако,
 * не пошёл бы ни в ту, ни в другую. Теперь всё «про меня в Point» лежит за одним словом; аккаунт и
 * круг устройств стали разделом внутри и переехали как есть.
 *
 * Дверь «Новый объект» (#456) — та, которой не было вовсе: пять источников (камера, голос, буфер,
 * место, файл из чужих рук) жили за плиткой шторки, а плитку надо было самому найти в редакторе.
 * При этом один источник из пяти — «Принять файл» — успел получить собственную иконку здесь;
 * теперь он стоит среди своих, а не отдельно. Меню это не заводит: экран по-прежнему отвечает на
 * один вопрос, просто первый ответ на него — «объекта ещё нет, вот откуда его взять».
 *
 * Строка «Посмотреть на примере» (#210) — второй ответ на тот же вопрос, для того, у кого объекта
 * нет и в руках: снимок из ресурсов Point, открывающийся обычным объектом. Стоит она только на
 * пустом доме и только ниже главной двери — см. [ExampleDoor].
 */
@Composable
fun HomeScreen(
    recent: List<HistoryEntry>,
    onOpen: (HistoryEntry) -> Unit,
    onSettings: () -> Unit,
    /** Дверь к выбору источника (#456): камера, голос, буфер, место, файл из чужих рук. */
    onNewObject: () -> Unit = {},
    /** Путь к примеру (#210): готовый объект из ресурсов Point для того, у кого своего ещё нет. */
    onExample: () -> Unit = {},
    /** Имена источников — подпись двери. Приходят от самих источников, здесь не переписаны. */
    sourceLabels: List<String> = emptyList(),
    onClear: () -> Unit = {},
    clipboard: String? = null,
    onUseClipboard: (String) -> Unit = {},
    onDismissClipboard: () -> Unit = {},
    crashReport: String? = null,
    onSendCrash: (String) -> Unit = {},
    onDismissCrash: () -> Unit = {},
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
            // Одна дверь вместо двух (#544). Подпись обязана называть то, что за ней ПРАВДА лежит:
            // «AI-ключ» называл одну настройку из пяти, «Устройства» — половину аккаунта. Слово
            // «Настройки» приходит из общего места (`SETTINGS_TITLE`): им же назван экран за дверью
            // и им же зовёт человека отказ по ключу.
            PortalDoor(
                label = SETTINGS_TITLE,
                onClick = onSettings,
                icon = bubbleIcon("settings"),
                accent = bubbleColor("settings"),
            )
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
                        // Первое, что человек читает, обязано отвечать на «что это такое».
                        // Прежняя строка звала поделиться объектом — а слова «объект» он ещё не
                        // знает; и рассказывать про AI-ключ раньше, чем про сам продукт, значило
                        // начинать знакомство с того, чего Point без чужого сервиса не умеет.
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
                    // Пример стоит ТОЛЬКО здесь, на пустом доме (#210): у человека с первым своим
                    // объектом он уже спрошен и отвечен, и навязывать его во второй раз незачем.
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
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                if (!aiKeySet) {
                    // Ниже главной двери, а не над ней: сначала то, ради чего Point открыли.
                    item { ConnectAiRow(onConnect = onSettings, modifier = Modifier.padding(bottom = 8.dp)) }
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
 * «Посмотреть на примере» (#210) — для того, у кого объекта в руках ещё нет.
 *
 * Первый экран умеет сказать, что такое Point, но проверить сказанное человеку было не на чем:
 * своего файла у него нет, а идти за ним — значит выйти из приложения в момент знакомства.
 * Строка кладёт во флоу готовый снимок из ресурсов, и дальше это обычный объект: те же действия,
 * тот же экран, та же уборка.
 *
 * **Не светится, и это решение, а не экономия.** Светящаяся строка на доме одна — «Новый объект»:
 * главное здесь всё-таки принести своё, а не смотреть чужое. Две одинаково ярких двери рядом
 * читались бы как равный выбор, и человек, у которого объект в руках уже есть, честно тратил бы
 * первый тап на пример. Стоит она ниже по той же причине.
 *
 * Ни тура, ни карусели, ни подсветки кнопок за ней нет и не будет: обещание #210 дословно —
 * «тестовый объект на первом запуске, это и есть вся песочница».
 */
@Composable
private fun ExampleDoor(onClick: () -> Unit, modifier: Modifier = Modifier) {
    PortalRow(
        title = "Посмотреть на примере",
        subtitle = EXAMPLE_DOOR_WHAT,
        onClick = onClick,
        // Знак чтения текста: ровно то, что случится с примером на телефоне через пару секунд
        // после открытия. Обещание строки и знак над ней говорят одно.
        icon = bubbleIcon("ocr"),
        accent = bubbleColor("ocr"),
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
        // Без «пара минут»: выпуск ключа у чужого сервиса за пару минут не делается, а обещание,
        // которое человек проверит первым же действием, дороже сэкономленной строки. Короткий
        // заголовок ещё и перестал ломаться на две строки поверх подписи.
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
                    text = historySubtitle(
                        name = entry.name,
                        kind = kindLabel(entry.kind),
                        // Время говорит по-русски всегда (дизайн-ревью 04.08.2026): системный
                        // DateUtils берёт язык телефона, и на английской системе строка выходила
                        // наполовину чужой — «Изображение · 3 hours ago».
                        ago = agoLabel(System.currentTimeMillis() - entry.epochMillis),
                    ),
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

/**
 * Вторая строка «Недавнего»: чем объект является и когда он появился.
 *
 * Вид не называется дважды (#533). С тех пор как запись и снимок называют себя сами («Запись,
 * 4 авг 19:25»), прежняя строка давала «Запись · 3 часа назад» прямо под словом «Запись» — вид
 * повторялся, а сказать ему было уже нечего. Тогда остаётся только время: имя вид уже назвало.
 */
internal fun historySubtitle(name: String?, kind: String, ago: String): String =
    if (name != null && name.startsWith(kind, ignoreCase = true)) ago else "$kind · $ago"

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
