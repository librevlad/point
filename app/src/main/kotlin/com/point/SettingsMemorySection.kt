package com.point

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.point.core.flow.MEMORY_TITLE
import com.point.core.ui.ScreenHeader

/** Раздел «Что Point помнит» на телефоне (#834). */
/** Сколько объектов Point помнит, сколько это занимает и как забыть (#821). */
@Composable
internal fun MemorySection(
    memory: com.point.core.flow.HistoryFootprint?,
    onForgetAll: () -> Unit,
    onBack: () -> Unit,
) {
    BackToList(onBack)
    ScreenHeader(title = MEMORY_TITLE, modifier = Modifier.padding(bottom = 9.dp))

    Text(
        memoryLine(memory),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface,
    )
    listOf(MEMORY_WHAT, DROP_LINKS_LIVE).forEach {
        Text(
            it,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
    }

    var asking by rememberSaveable { mutableStateOf(false) }
    if (asking) {
        Text(
            CLEAR_RECENT_WHAT,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 10.dp),
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { asking = false }) {
                Text("Отмена", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = { asking = false; onForgetAll() }) {
                Text(CLEAR_RECENT_CONFIRM, color = MaterialTheme.colorScheme.error)
            }
        }
    } else {
        TextButton(onClick = { asking = true }, modifier = Modifier.padding(top = 8.dp)) {
            Text(FORGET_ALL, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
