package com.point

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.point.core.ui.ScreenHeader

/** Раздел «Поведение Point» на телефоне (#834). */
@Composable
internal fun AppSection(
    soundEnabled: Boolean,
    onToggleSound: (Boolean) -> Unit,
    onBack: () -> Unit,
) {
    BackToList(onBack)
    ScreenHeader(title = APP_SECTION_TITLE, modifier = Modifier.padding(bottom = 9.dp))

    SwitchCard(
        title = SOUND_TITLE,
        description = "Тихий фирменный отклик на каждое действие. Вибрация управляется системной настройкой касаний.",
        checked = soundEnabled,
        onCheckedChange = onToggleSound,
    )
}
