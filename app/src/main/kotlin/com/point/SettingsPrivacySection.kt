package com.point

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.point.core.flow.PRIVACY_SECTION_TITLE
import com.point.core.flow.PRIVACY_SETTING_HINT
import com.point.core.flow.PRIVACY_SETTING_TITLE
import com.point.core.flow.PrivacyLevel
import com.point.core.flow.YOLO_TITLE
import com.point.core.flow.YOLO_WHAT
import com.point.core.ui.PortalRow
import com.point.core.ui.ScreenHeader
import com.point.core.ui.SectionLabel

/** Раздел «Отправка и приватность» на телефоне (#834). */
@Composable
internal fun PrivacySection(
    cloudEnabled: Boolean,
    onToggleCloud: (Boolean) -> Unit,
    yoloEnabled: Boolean,
    onToggleYolo: (Boolean) -> Unit,
    privacyLevel: PrivacyLevel,
    onPickPrivacyLevel: (PrivacyLevel) -> Unit,
    onBack: () -> Unit,
) {
    BackToList(onBack)
    ScreenHeader(title = PRIVACY_SECTION_TITLE, modifier = Modifier.padding(bottom = 9.dp))

    SwitchCard(
        title = "Отправка в облако",
        // #688, охота 2026-08-10: обещание «с названием того, куда он уедет» система
        // не выполняла — подписи действий называли только «сервис». Кто читает объект,
        // теперь видно в «Ключи AI» (#699): вся цепочка по порядку, с последним фактом.
        description = "Разрешает показывать объект моделям — по вашему тапу. Выключите, и " +
            "Point спросит заново. Выложить файл по открытой ссылке этим тумблером нельзя: " +
            "про такое спрашивают каждый раз.",
        checked = cloudEnabled,
        onCheckedChange = onToggleCloud,
    )

    Spacer(Modifier.height(6.dp))
    SwitchCard(
        title = YOLO_TITLE,
        description = YOLO_WHAT,
        checked = yoloEnabled,
        onCheckedChange = onToggleYolo,
    )

    Spacer(Modifier.height(6.dp))
    SectionLabel(PRIVACY_SETTING_TITLE)
    Text(
        PRIVACY_SETTING_HINT,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    PrivacyLevel.entries.forEachIndexed { index, level ->
        PortalRow(
            title = level.title,

            subtitle = level.what,
            onClick = { onPickPrivacyLevel(level) },

            primary = level == privacyLevel,
            chevron = false,
            subtitleMaxLines = 4,
            appearIndex = index,
        )
    }
}
