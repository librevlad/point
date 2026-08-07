package com.point.source

import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.AddToHomeScreen
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.point.R
import com.point.ShareActivity
import com.point.core.ui.PortalColumnWidth
import com.point.core.ui.PortalRow
import com.point.core.ui.ScreenHeader
import com.point.core.ui.bubbleColor
import com.point.core.ui.bubbleIcon
import com.point.core.ui.theme.PointTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.launch

@AndroidEntryPoint
class SourcePickerActivity : ComponentActivity() {

    @Inject lateinit var sources: Set<@JvmSuppressWildcards ObjectSource>

    private var pending: ObjectSource? = null

    private var blocked by mutableStateOf<String?>(null)

    private var tileOffer by mutableStateOf(false)

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val source = pending
            pending = null
            if (source == null) {
                lostToMemory()
                return@registerForActivityResult
            }
            when (permissionOutcome(result, ::shouldShowRequestPermissionRationale)) {
                PermissionOutcome.GRANTED -> launchSource(source)

                PermissionOutcome.DENIED -> {
                    Toast.makeText(this, "Без этого доступа не получится", Toast.LENGTH_SHORT).show()
                    finish()
                }

                PermissionOutcome.BLOCKED -> blocked = source.label
            }
        }

    private val launcher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val source = pending
        pending = null
        if (source == null) {

            lostToMemory()
        } else {
            lifecycleScope.launch { deliver(source.read(this@SourcePickerActivity, result.data)) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        pending = restoredSource(sources, savedInstanceState?.getString(STATE_SOURCE))
        pending?.restoreState(savedInstanceState?.getString(STATE_SOURCE_STATE))
        blocked = savedInstanceState?.getString(STATE_BLOCKED)
        val visible = sources.filter { it.isAvailable(this) }.sortedBy { it.label }
        tileOffer = tileOfferVisible(Build.VERSION.SDK_INT, shadeTileKnown(this))
        setContent {
            PointTheme {
                SourcePickerScreen(
                    sources = visible,
                    onPick = ::start,
                    blocked = blocked,
                    onOpenSettings = ::openAppSettings,
                    onDismissBlocked = ::finish,
                    tileOffer = tileOffer,
                    onAddTile = ::addTile,
                )
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        pending?.let {
            outState.putString(STATE_SOURCE, it.id)
            outState.putString(STATE_SOURCE_STATE, it.saveState())
        }
        blocked?.let { outState.putString(STATE_BLOCKED, it) }
    }

    private fun start(source: ObjectSource) {
        val granted = source.permissions.filter {
            checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }.toSet()
        val missing = missingPermissions(source.permissions, granted)
        if (missing.isNotEmpty()) {
            pending = source
            permissionLauncher.launch(missing.toTypedArray())
            return
        }
        launchSource(source)
    }

    private fun launchSource(source: ObjectSource) {
        lifecycleScope.launch {
            val request = source.request(this@SourcePickerActivity)
            if (request == null) {
                deliver(source.read(this@SourcePickerActivity, null))
                return@launch
            }
            pending = source
            launcher.launch(request)
        }
    }

    private fun addTile() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val bar = getSystemService(StatusBarManager::class.java)
        if (bar == null) {
            onTileAnswer(TileAddOutcome.FAILED)
            return
        }
        bar.requestAddTileService(
            ComponentName(this, PointTileService::class.java),
            getString(R.string.app_name),
            Icon.createWithResource(this, R.drawable.ic_tile_point),
            mainExecutor,
        ) { result -> onTileAnswer(tileAddOutcome(result)) }
    }

    private fun onTileAnswer(outcome: TileAddOutcome) {
        if (outcome.tilePresent) {
            rememberShadeTile(this, added = true)
            tileOffer = false
        }
        tileAddMessage(outcome)?.let { Toast.makeText(this, it, Toast.LENGTH_SHORT).show() }
    }

    private fun openAppSettings() {
        val opened = runCatching {
            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", packageName, null),
                ),
            )
        }.isSuccess
        if (opened) {
            blocked = null
        } else {
            Toast.makeText(this, SETTINGS_CLOSED, Toast.LENGTH_LONG).show()
        }
    }

    private fun lostToMemory() {
        Toast.makeText(this, LOST_TO_MEMORY, Toast.LENGTH_LONG).show()
    }

    private fun deliver(produced: Produced?) {
        if (produced == null) {
            finish()
            return
        }
        startActivity(
            Intent(this, ShareActivity::class.java)
                .setAction(Intent.ACTION_SEND)
                .setType(produced.mime)
                .putExtra(Intent.EXTRA_STREAM, Uri.parse(produced.uri))

                .apply { produced.name?.let { putExtra(EXTRA_OBJECT_NAME, it) } }
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION),
        )
        finish()
    }

    companion object {
        private const val STATE_SOURCE = "com.point.source.PENDING_ID"
        private const val STATE_SOURCE_STATE = "com.point.source.PENDING_STATE"
        private const val STATE_BLOCKED = "com.point.source.BLOCKED_LABEL"

        internal const val LOST_TO_MEMORY =
            "Point выгрузили из памяти, пока работало другое приложение, — сделанное там не дошло. " +
                "Попробуйте ещё раз."

        internal const val SETTINGS_CLOSED =
            "Настройки не открылись — включите доступ вручную: Приложения → Point → Разрешения"
    }
}

internal fun restoredSource(sources: Collection<ObjectSource>, id: String?): ObjectSource? =
    if (id.isNullOrBlank()) null else sources.firstOrNull { it.id == id }

@Composable
internal fun SourcePickerScreen(
    sources: List<ObjectSource>,
    onPick: (ObjectSource) -> Unit,
    modifier: Modifier = Modifier,
    blocked: String? = null,
    onOpenSettings: () -> Unit = {},
    onDismissBlocked: () -> Unit = {},
    tileOffer: Boolean = false,
    onAddTile: () -> Unit = {},
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.88f))
            .systemBarsPadding()

            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.widthIn(max = PortalColumnWidth).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            if (blocked == null) {
                ScreenHeader(title = "Что превратить в объект?", modifier = Modifier.padding(bottom = 9.dp))
                sources.forEachIndexed { index, source ->
                    PortalRow(
                        title = source.label,
                        onClick = { onPick(source) },
                        icon = bubbleIcon(source.icon),
                        accent = bubbleColor(source.icon),
                        appearIndex = index,
                    )
                }
                if (tileOffer) {
                    PortalRow(
                        title = "Поставить плитку в шторку",
                        subtitle = "Тот же выбор, но не открывая Point",
                        onClick = onAddTile,
                        icon = Icons.AutoMirrored.Filled.AddToHomeScreen,
                        accent = MaterialTheme.colorScheme.onSurfaceVariant,
                        appearIndex = sources.size,
                    )
                }
            } else {

                ScreenHeader(
                    title = "Доступ выключен насовсем",
                    subtitle = "«$blocked» без него не начнёт. Система больше не спросит — " +
                        "включить можно только в настройках Point.",
                    modifier = Modifier.padding(bottom = 9.dp),
                )
                PortalRow(
                    title = "Открыть настройки Point",
                    onClick = onOpenSettings,
                    icon = bubbleIcon("open"),
                    accent = bubbleColor("open"),
                    appearIndex = 0,
                )

                TextButton(onClick = onDismissBlocked, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Text("Не сейчас", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

private fun previewSource(sourceId: String, sourceLabel: String, iconKey: String) = object : ObjectSource {
    override val id = sourceId
    override val label = sourceLabel
    override val icon = iconKey
    override fun isAvailable(context: android.content.Context) = true
    override suspend fun request(context: android.content.Context): Intent? = null
    override suspend fun read(context: android.content.Context, data: Intent?): Produced? = null
}

@Preview(name = "Источник объекта · шторка (#114)", showBackground = true, backgroundColor = 0xFFFFFFFF)
@Composable
private fun PreviewSourcePicker() = PointTheme {
    SourcePickerScreen(
        sources = listOf(
            previewSource("clipboard", "Буфер обмена", "copy"),
            previewSource("camera", "Камера", "camera"),
            previewSource("voice", "Голос", "transcribe"),
            previewSource("location", "Место", "map"),
            previewSource("receive", "Принять файл", "link"),
        ),
        onPick = {},
        tileOffer = true,
    )
}

@Preview(name = "Источник объекта · доступ закрыт насовсем (#455)", showBackground = true, backgroundColor = 0xFFFFFFFF)
@Composable
private fun PreviewSourceBlocked() = PointTheme {
    SourcePickerScreen(sources = emptyList(), onPick = {}, blocked = "Место")
}
