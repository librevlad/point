package com.point

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.point.core.flow.AI_KEY_WHY
import com.point.core.flow.AI_PROVIDERS
import com.point.core.flow.AiFact
import com.point.core.flow.AiOutcome
import com.point.core.flow.AiProvider
import com.point.core.flow.AiServiceLine
import com.point.core.flow.KeyVerdict
import com.point.core.flow.MY_DEVICES_TITLE
import com.point.core.flow.OWN_SERVICE_ID
import com.point.core.flow.PRIVACY_SETTING_HINT
import com.point.core.flow.PRIVACY_SETTING_TITLE
import com.point.core.flow.PrivacyLevel
import com.point.core.flow.SETTINGS_TITLE
import com.point.core.flow.UserAiKey
import com.point.core.flow.UserAiKeys
import com.point.core.flow.YOLO_TITLE
import com.point.core.flow.YOLO_WHAT
import com.point.core.flow.aiCheckedLine
import com.point.core.flow.aiKeysSummary
import com.point.core.flow.aiServiceLines
import com.point.core.flow.looksLikeApiKey
import com.point.core.ui.Outcome
import com.point.core.ui.OutcomeBanner
import com.point.core.ui.OutcomeCard
import com.point.core.ui.PortalColumnWidth
import com.point.core.ui.PortalRow
import com.point.core.ui.ScreenHeader
import com.point.core.ui.SectionLabel
import com.point.core.ui.bubbleColor
import com.point.core.ui.bubbleIcon
import com.point.core.ui.portalCard
import com.point.core.ui.theme.PointTheme

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
