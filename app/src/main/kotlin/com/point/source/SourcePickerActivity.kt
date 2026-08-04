package com.point.source

import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
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

/**
 * «Что превратить в объект?» — экран выбора источника (#246).
 *
 * Открывается из плитки шторки и с домашнего экрана (#456): пока дверь была одна и та пряталась в
 * редакторе плиток, четыре источника из пяти не видел никто. Живёт поверх того, откуда пришли, и
 * исчезает, как только объект родился. Здесь же решается вопрос, из-за которого этот экран вообще
 * обязан быть активити, а не сервисом: буфер обмена Android отдаёт только приложению на переднем
 * плане (тот же приём, что у `ClipboardSyncActivity`).
 */
@AndroidEntryPoint
class SourcePickerActivity : ComponentActivity() {

    @Inject lateinit var sources: Set<@JvmSuppressWildcards ObjectSource>

    private var pending: ObjectSource? = null

    /** Показывать ли строку «Поставить плитку в шторку» — правило живёт в `ShadeTile.kt`. */
    private var tileOffer by mutableStateOf(false)

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val source = pending
            pending = null
            when {
                source == null -> finish()
                result.values.any { !it } -> {
                    // Отказ назван словами: молча закрыться — значит оставить человека гадать,
                    // сломалось оно или он сам только что запретил.
                    Toast.makeText(this, "Без этого доступа не получится", Toast.LENGTH_SHORT).show()
                    finish()
                }
                else -> launchSource(source)
            }
        }

    private val launcher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val source = pending
        pending = null
        if (source == null) {
            finish()
        } else {
            lifecycleScope.launch { deliver(source.read(this@SourcePickerActivity, result.data)) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val visible = sources.filter { it.isAvailable(this) }.sortedBy { it.label }
        tileOffer = tileOfferVisible(Build.VERSION.SDK_INT, shadeTileKnown(this))
        setContent {
            PointTheme {
                SourcePickerScreen(
                    sources = visible,
                    onPick = ::start,
                    tileOffer = tileOffer,
                    onAddTile = ::addTile,
                )
            }
        }
    }

    /**
     * Попросить систему предложить плитку (#456). Дальше решает человек в системном диалоге —
     * поставить плитку молча Point не может и не должен.
     */
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

    /**
     * Разрешение спрашивается по тапу и только то, которого нет: просить уже выданное — то самое
     * назойливое трение, от которого Point уходит.
     */
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

    /** Объект уходит в обычную дверь: шторка его не обрабатывает (#246). */
    private fun deliver(produced: Produced?) {
        if (produced == null) {
            finish() // отказ уже назван источником
            return
        }
        startActivity(
            Intent(this, ShareActivity::class.java)
                .setAction(Intent.ACTION_SEND)
                .setType(produced.mime)
                .putExtra(Intent.EXTRA_STREAM, Uri.parse(produced.uri))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION),
        )
        finish()
    }
}

/**
 * Сам экран — чистый и без Android-обвязки, поэтому целиком рисуется в `@Preview` (#114).
 *
 * Источник здесь выглядит **строкой действия с экрана объекта**: плита со свечением своего цвета,
 * название, шеврон. Так и должно быть — «снять камерой» и «распознать текст» это одно и то же
 * движение, только по разные стороны рождения объекта. Раньше здесь стоял ряд стандартных
 * Material-кнопок: фиолетовые таблетки по центру, без иконок, набранные не тем шрифтом.
 *
 * Затемнение — тоже часть починки. Окно у экрана прозрачное (он открывается поверх чужого
 * приложения из шторки), а фона не было вовсе: белый текст ложился на чужой светлый экран и
 * пропадал. Теперь под карточкой лежит ФОН дизайн-системы, приглушённый до просвечивания: видно,
 * откуда пришли, и читается то, что написано.
 *
 * [tileOffer] — последняя, тихая строка: «поставить плитку в шторку» (#456). Стоит она именно
 * здесь, потому что здесь человек только что сказал, чего хочет, — а плитка это тот же экран, но
 * без открывания Point. Ярче источников она не бывает: это ускорение, а не действие.
 */
@Composable
internal fun SourcePickerScreen(
    sources: List<ObjectSource>,
    onPick: (ObjectSource) -> Unit,
    modifier: Modifier = Modifier,
    tileOffer: Boolean = false,
    onAddTile: () -> Unit = {},
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.88f))
            .systemBarsPadding()
            // Источников пятеро, и к ним прибавилась строка про плитку (#456): на невысоком экране
            // последний источник уходил за кромку молча — то есть снова становился невидимым.
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.widthIn(max = PortalColumnWidth).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(11.dp),
        ) {
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
        }
    }
}

/** Источник для превью: экрану от него нужны только имя и иконка. */
private fun previewSource(sourceId: String, sourceLabel: String, iconKey: String) = object : ObjectSource {
    override val id = sourceId
    override val label = sourceLabel
    override val icon = iconKey
    override fun isAvailable(context: android.content.Context) = true
    override suspend fun request(context: android.content.Context): Intent? = null
    override suspend fun read(context: android.content.Context, data: Intent?): Produced? = null
}

// Фон превью — белый нарочно: так видно, что затемнение делает свою работу поверх чужого светлого
// приложения. Именно на нём прежний экран и терялся.
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
        onAddTile = {},
    )
}
