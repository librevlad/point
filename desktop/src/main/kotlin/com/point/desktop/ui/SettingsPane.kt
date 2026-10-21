package com.point.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.point.core.flow.CircleDevice
import com.point.desktop.DesktopState
import com.point.desktop.PcConfig

/**
 * Настройки в окне компьютера: какой раздел открыт и что в нём показывать (#836).
 *
 * Сами экраны разделов живут в `SettingsScreen.kt`; здесь только переходы между ними.
 */

@Composable
internal fun SettingsPane(
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
    onRightClick: suspend (Boolean) -> Boolean?,
    rightClickHolds: suspend (Boolean) -> Boolean?,
    onSweepNow: () -> Int,

    /** Браузер компьютера: сайт сервиса ключа и политика конфиденциальности (#1487). */
    onOpenUrl: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) = Column(modifier) {
    // Настройки — список разделов со своими экранами, как на телефоне (#886). Слово
    // «Настройки» при этом сказано один раз: шапкой окна, а не ещё и меткой внутри (#878).
    var swept by remember { mutableStateOf<Int?>(null) }
    val signedInAs by account.account.collectAsState()
    var cloudAllowed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(page) {
        cloudAllowed = state.consent?.allowed(com.point.core.flow.CloudScope.MODELS) == true
    }

    PaneHeader(
        title = page.title,
        onBack = { if (page == SettingsPage.ROOT) onBack() else onPage(SettingsPage.ROOT) },
    )
    Column(
        modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp).padding(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        when (page) {
            SettingsPage.ROOT -> SettingsRoot(
                config = config,
                devices = circle.size,
                email = signedInAs?.email.orEmpty(),
                signedIn = signedInAs != null,
                onSave = onSave,
                onRightClick = onRightClick,
                rightClickHolds = rightClickHolds,
                onOpen = onPage,
            )

            SettingsPage.DEVICES -> SettingsDevices(config = config, onSave = onSave) {
                // Без входа здесь — дверь входа: подключать устройства, а не список пустоты.
                if (signedInAs == null) {
                    ConnectDevicesPane(error = error, onSignIn = account::signIn)
                } else {
                    MyDevicesPane(
                        email = signedInAs?.email.orEmpty(),
                        devices = circle,
                        busy = busy,
                        error = error,
                        onRevoke = account::revoke,
                        onSignOut = { onWipe(); account.signOut() },
                    )
                }
            }

            SettingsPage.KEYS -> SettingsKeys(config = config, onSave = onSave, onOpenUrl = onOpenUrl)

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
                onOpenPolicy = { onOpenUrl(com.point.core.flow.PointServer.PRIVACY_URL) },
            )

            SettingsPage.DATA -> SettingsData(swept = swept) { swept = onSweepNow() }
        }
    }
}
