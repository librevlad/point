package com.point.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import kotlinx.coroutines.launch
import com.point.core.flow.yieldLabel
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.awtTransferable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.point.core.flow.CircleDevice
import com.point.core.model.ObjectKind
import com.point.core.ui.bubbleColor
import com.point.core.ui.kindLabel
import com.point.desktop.DesktopState
import com.point.desktop.InboxItem
import com.point.desktop.PcConfig
import java.awt.datatransfer.DataFlavor
import java.io.File
import java.time.ZoneId
import kotlinx.coroutines.flow.StateFlow

/**
 * Настройки в окне компьютера: какой раздел открыт и что в нём показывать (#836).
 *
 * Сами экраны разделов живут в `SettingsScreen.kt`; здесь только переходы между ними.
 */

@Composable
internal fun CompactSettings(
    state: DesktopState,

    // Какой раздел открыт — знание окна, не этого экрана: Esc в корне окна обязан вести
    // себя как «←» текущего раздела (#1025).
    page: SettingsPage,
    onPage: (SettingsPage) -> Unit,
    config: PcConfig,
    account: com.point.desktop.DesktopAccount,
    circle: List<CircleDevice>,
    busy: Boolean,
    error: String?,
    onWipe: () -> Unit,
    onSave: (PcConfig) -> Unit,
    onRightClick: suspend (Boolean) -> Boolean,
    rightClickHolds: suspend (Boolean) -> Boolean?,
    onSweepNow: () -> Int,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) = Column(modifier) {
    // Настройки — список разделов со своими экранами, как на телефоне (#886). Слово
    // «Настройки» при этом сказано один раз: шапкой окна, а не ещё и меткой внутри (#878).
    var swept by remember { mutableStateOf<Int?>(null) }
    var cloudAllowed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(page) {
        cloudAllowed = state.consent?.allowed(com.point.core.flow.CloudScope.MODELS) == true
    }

    CompactHeader(
        title = page.title,
        onBack = { if (page == SettingsPage.ROOT) onBack() else onPage(SettingsPage.ROOT) },
        onHide = onBack,
    )
    Column(
        modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp).padding(bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        when (page) {
            SettingsPage.ROOT -> SettingsRoot(
                config = config,
                devices = circle.size,
                email = account.current()?.email.orEmpty(),
                onSave = onSave,
                onRightClick = onRightClick,
                rightClickHolds = rightClickHolds,
                onOpen = onPage,
            )

            SettingsPage.DEVICES -> SettingsDevices(config = config, onSave = onSave) {
                MyDevicesPane(
                    email = account.current()?.email.orEmpty(),
                    devices = circle,
                    busy = busy,
                    error = error,
                    onRevoke = account::revoke,
                    onSignOut = { onWipe(); account.signOut() },
                )
            }

            SettingsPage.KEYS -> SettingsKeys(config = config, onSave = onSave)

            SettingsPage.PRIVACY -> SettingsPrivacy(
                allowed = cloudAllowed,
                level = config.privacy,
                onRevoke = {
                    scope.launch {
                        state.consent?.revoke(com.point.core.flow.CloudScope.MODELS)
                        cloudAllowed = false
                    }
                },
                onPickLevel = { onSave(config.copy(privacy = it)) },
            )

            SettingsPage.DATA -> SettingsData(swept = swept) { swept = onSweepNow() }
        }
    }
}
