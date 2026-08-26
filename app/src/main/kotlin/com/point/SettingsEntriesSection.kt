package com.point

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.point.core.ui.ScreenHeader

/** Раздел «Точки входа» на телефоне (#834). */
/** Откуда Point открывается — про плитку человек мог не знать вовсе (#821). */
@Composable
internal fun EntriesSection(tileAdded: Boolean, onBack: () -> Unit) {
    BackToList(onBack)
    ScreenHeader(title = ENTRIES_TITLE, modifier = Modifier.padding(bottom = 9.dp))

    listOf(SHARE_ENTRY, if (tileAdded) TILE_ENTRY_ON else TILE_ENTRY_OFF).forEach {
        Text(
            it,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 6.dp),
        )
    }
}
