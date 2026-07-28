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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Settings
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
import com.point.core.ui.kindIcon
import com.point.core.ui.kindLabel
import com.point.core.ui.understoodFacts
import com.point.executors.Bitmaps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Point's home: the recent objects you brought in. Tap one to keep working with it —
 * no going back to the source app to share again (the metric: fewer switches). A
 * single key affordance opens the bring-your-own AI-key screen; there is no menu.
 */
@Composable
fun HomeScreen(
    recent: List<HistoryEntry>,
    onOpen: (HistoryEntry) -> Unit,
    onSettings: () -> Unit,
    onPc: () -> Unit = {},
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
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().systemBarsPadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            IconButton(onClick = onPc) {
                Icon(
                    imageVector = Icons.Filled.Computer,
                    contentDescription = "Компьютер",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onSettings) {
                Icon(
                    imageVector = Icons.Filled.Settings,
                    contentDescription = "Ваш AI-ключ",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
            Box(Modifier.weight(1f).fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
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
                        "Поделитесь объектом — он появится здесь",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
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
