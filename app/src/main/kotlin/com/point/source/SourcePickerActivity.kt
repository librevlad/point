package com.point.source

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
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
 * «Что превратить в объект?» — экран выбора источника из шторки (#246).
 *
 * Живёт поверх чужого приложения и исчезает, как только объект родился. Здесь же решается вопрос,
 * из-за которого этот экран вообще обязан быть активити, а не сервисом: буфер обмена Android
 * отдаёт только приложению на переднем плане (тот же приём, что у `ClipboardSyncActivity`).
 *
 * Экран прозрачный и лёгкий, а ждёт он за камерой — самым тяжёлым приложением телефона. Значит,
 * при нехватке памяти выгружают первым его, и всё, что он держал обычным полем, к возвращению
 * мертво. Поэтому «кого запускали» и «что тот источник о себе помнил» переживают пересоздание
 * через `onSaveInstanceState` (#454) — тем же приёмом, каким ящик приёма переживает поворот (#114).
 */
@AndroidEntryPoint
class SourcePickerActivity : ComponentActivity() {

    @Inject lateinit var sources: Set<@JvmSuppressWildcards ObjectSource>

    /** Кого запускали и ждём обратно. Переживает выгрузку экрана — иначе снятый кадр пропадёт. */
    private var pending: ObjectSource? = null

    /**
     * Источник, которому доступ закрыт насовсем (#455), — его именем названы слова на экране.
     * `null` — обычный выбор источника.
     */
    private var blocked by mutableStateOf<String?>(null)

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
                // Отказ назван словами: молча закрыться — значит оставить человека гадать,
                // сломалось оно или он сам только что запретил. Спросят снова — значит путь
                // прежний, и экран уходит с дороги.
                PermissionOutcome.DENIED -> {
                    Toast.makeText(this, "Без этого доступа не получится", Toast.LENGTH_SHORT).show()
                    finish()
                }
                // А здесь тапать заново бессмысленно: система откажет мгновенно и молча, окна
                // человек больше не увидит. Единственная настоящая дорога — настройки, и экран
                // показывает её вместо того же тоста по кругу.
                PermissionOutcome.BLOCKED -> blocked = source.label
            }
        }

    private val launcher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val source = pending
        pending = null
        if (source == null) {
            // Результат пришёл — значит что-то запускали; ждать его некому — значит экран
            // выгрузили. Раньше здесь стоял голый `finish()`: кадр снят, файл записан, объект не
            // родился, человек ничего не узнал (#454).
            lostToMemory()
        } else {
            lifecycleScope.launch { deliver(source.read(this@SourcePickerActivity, result.data)) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Кого ждали до выгрузки — и что он о себе помнил. Без второй строки восстановленная
        // камера не знает, куда писался кадр, и снимок теряется ровно так же, как без первой.
        pending = restoredSource(sources, savedInstanceState?.getString(STATE_SOURCE))
        pending?.restoreState(savedInstanceState?.getString(STATE_SOURCE_STATE))
        blocked = savedInstanceState?.getString(STATE_BLOCKED)
        val visible = sources.filter { it.isAvailable(this) }.sortedBy { it.label }
        setContent {
            PointTheme {
                SourcePickerScreen(
                    sources = visible,
                    onPick = ::start,
                    blocked = blocked,
                    onOpenSettings = ::openAppSettings,
                    onDismissBlocked = ::finish,
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

    /**
     * Единственная дорога дальше, когда доступ закрыт насовсем (#455): системные настройки Point.
     *
     * Экран не закрывается сам — человек возвращается «назад» с уже включённым доступом и делает
     * ровно один тап. Если настройки не открылись, это тоже сказано словами: молчание здесь —
     * та же ловушка, из которой мы только что вышли.
     */
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

    /**
     * Сделанное в чужом приложении не дошло, потому что Point выгрузили из памяти (#454).
     *
     * Экран остаётся открытым: повторить — один тап, и это честнее, чем закрыться на ровном месте.
     * Слова обязаны прозвучать даже теперь, когда восстановление сделано: сюда попадают ровно те
     * случаи, где сохраниться не дали, — а такое молчание неотличимо от поломки.
     */
    private fun lostToMemory() {
        Toast.makeText(this, LOST_TO_MEMORY, Toast.LENGTH_LONG).show()
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

    companion object {
        private const val STATE_SOURCE = "com.point.source.PENDING_ID"
        private const val STATE_SOURCE_STATE = "com.point.source.PENDING_STATE"
        private const val STATE_BLOCKED = "com.point.source.BLOCKED_LABEL"

        /** Потерянное названо словами, а не молчанием (#454). */
        internal const val LOST_TO_MEMORY =
            "Point выгрузили из памяти, пока работало другое приложение, — сделанное там не дошло. " +
                "Попробуйте ещё раз."

        /** Даже отказ настроек открыться назван вслух (#455): иначе это второй тупик подряд. */
        internal const val SETTINGS_CLOSED =
            "Настройки не открылись — включите доступ вручную: Приложения → Point → Разрешения"
    }
}

/**
 * Источник, переживший пересоздание экрана (#454), — или `null`, если ждать было некого.
 *
 * Отдельной функцией, а не строкой внутри `onCreate`: решение «кого мы ждём обратно» и есть то
 * место, где снятый кадр терялся молча. Источник ищется по устойчивому `id`, а не по месту в
 * наборе: набор собирает Hilt, и порядок в нём не обещан никем.
 */
internal fun restoredSource(sources: Collection<ObjectSource>, id: String?): ObjectSource? =
    if (id.isNullOrBlank()) null else sources.firstOrNull { it.id == id }

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
 * [blocked] — имя источника, которому доступ закрыт насовсем (#455). Выбирать тогда не из чего:
 * экран называет, что произошло, и даёт единственную настоящую дорогу — в настройки Point.
 */
@Composable
internal fun SourcePickerScreen(
    sources: List<ObjectSource>,
    onPick: (ObjectSource) -> Unit,
    modifier: Modifier = Modifier,
    blocked: String? = null,
    onOpenSettings: () -> Unit = {},
    onDismissBlocked: () -> Unit = {},
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.88f))
            .systemBarsPadding()
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
            } else {
                // Настройки — не подсказка мелким шрифтом внизу тоста, а такая же строка действия,
                // как всё остальное в Point: дорога дальше выглядит дорогой.
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
                // Из любого состояния есть выход (#114) — из этого тоже.
                TextButton(onClick = onDismissBlocked, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Text("Не сейчас", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
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
    )
}

// Тупик #455 в лицо: раньше на этом месте был тост «Без этого доступа не получится» — и всё.
@Preview(name = "Источник объекта · доступ закрыт насовсем (#455)", showBackground = true, backgroundColor = 0xFFFFFFFF)
@Composable
private fun PreviewSourceBlocked() = PointTheme {
    SourcePickerScreen(sources = emptyList(), onPick = {}, blocked = "Место")
}
