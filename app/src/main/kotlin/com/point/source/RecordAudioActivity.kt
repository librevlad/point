package com.point.source

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.point.core.ui.Outcome
import com.point.core.ui.OutcomeBanner
import com.point.core.ui.Portal
import com.point.core.ui.PortalColumnWidth
import com.point.core.ui.PortalRow
import com.point.core.ui.ScreenHeader
import com.point.core.ui.bubbleColor
import com.point.core.ui.bubbleIcon
import com.point.core.ui.theme.PointTheme
import dagger.hilt.android.AndroidEntryPoint
import java.io.File
import kotlinx.coroutines.delay

/**
 * Звукозапись **самим Point** (#246).
 *
 * Раньше голос добывался чужим диктофоном — системным намерением «записать звук». На телефоне
 * владельца (Samsung A34) на это намерение не отвечает **ни одно** приложение: диктофона в системе
 * нет вовсе. Источник честно прятался, и человек не видел его в списке — то есть возможности не
 * существовало ровно там, где она нужнее всего.
 *
 * Поэтому Point пишет сам. Цена названа прямо: он просит доступ к микрофону — раньше не просил
 * ничего. Просьба приходит по тапу, а не на старте: человек уже сказал, чего хочет.
 *
 * Формат — `m4a` (AAC), тот же, который читают и расшифровка, и модели общего назначения; заводить
 * свой формат ради записи было бы отдельной правдой о том, что мы вообще принимаем.
 */
@AndroidEntryPoint
class RecordAudioActivity : ComponentActivity() {

    private var recorder: MediaRecorder? = null
    private var target: File? = null

    private var recording by mutableStateOf(false)
    private var seconds by mutableIntStateOf(0)
    private var failure by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Путь переживает пересоздание экрана — та же болезнь, что чинили у камеры и приёма (#454):
        // запись идёт, систему прижало по памяти, и файл, в который она пишется, теряется молча.
        target = savedInstanceState?.getString(STATE_PATH)?.let(::File)
        setContent {
            PointTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    RecordTicker(recording) { seconds = it }
                    RecordScreen(
                        recording = recording,
                        seconds = seconds,
                        failure = failure,
                        onToggle = { if (recording) stopAndFinish() else start() },
                        onCancel = ::cancel,
                    )
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        target?.let { outState.putString(STATE_PATH, it.absolutePath) }
    }

    /** Уход с экрана обрывает запись: писать в фоне Point не умеет и обещать этого не должен. */
    override fun onPause() {
        super.onPause()
        if (recording && !isFinishing) stopAndFinish()
    }

    private fun start() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            // Разрешение спрашивает экран выбора источника — он умеет отличать «отказал сейчас» от
            // «закрыл навсегда» и знает дорогу в настройки (#455). Сюда мы попадаем, только если
            // доступ отозвали между выбором и запуском.
            failure = "Point не разрешили слушать микрофон — без этого записать нечего"
            return
        }
        val file = File(cacheDir, "record-${System.currentTimeMillis()}.m4a")
        val rec = runCatching {
            newRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(BITRATE)
                setAudioSamplingRate(SAMPLE_RATE)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
        }.getOrElse {
            // Микрофон занят чужим приложением, кодек отказал — причина называется словами, а не
            // молчаливым закрытием экрана.
            failure = "Не удалось начать запись — микрофон занят другим приложением"
            return
        }
        recorder = rec
        target = file
        failure = null
        seconds = 0
        recording = true
    }

    private fun newRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(this)
        else @Suppress("DEPRECATION") MediaRecorder()

    private fun stopAndFinish() {
        val file = target
        // `stop()` бросает, если записывать было нечего (тап «Стоп» через мгновение после «Запись»):
        // файл при этом остаётся битым, и отдавать его объектом нельзя.
        val ok = runCatching { recorder?.stop() }.isSuccess
        release()
        recording = false
        if (!ok || file == null || !file.isFile || file.length() == 0L) {
            file?.delete()
            failure = "Запись слишком короткая — ничего не записалось"
            return
        }
        setResult(RESULT_OK, Intent().putExtra(EXTRA_PATH, file.absolutePath).putExtra(EXTRA_MIME, MIME))
        finish()
    }

    private fun cancel() {
        if (recording) runCatching { recorder?.stop() }
        release()
        target?.delete()
        finish()
    }

    private fun release() {
        runCatching { recorder?.release() }
        recorder = null
    }

    override fun onDestroy() {
        super.onDestroy()
        release()
    }

    companion object {
        const val EXTRA_PATH = "com.point.record.PATH"
        const val EXTRA_MIME = "com.point.record.MIME"
        const val MIME = "audio/mp4"
        private const val STATE_PATH = "com.point.record.TARGET"
        private const val BITRATE = 128_000
        private const val SAMPLE_RATE = 44_100
    }
}

/** Сколько идёт запись, словами человека: «0:07», «1:42». */
internal fun recordClock(seconds: Int): String =
    "%d:%02d".format(seconds / 60, seconds % 60)

/**
 * Экран записи — языком портала.
 *
 * Кольцо горит, пока идёт запись, и притушено, пока не начали: тот же знак «работаем», что на
 * каждом долгом действии. Кнопка одна и меняет желание — «Записать» / «Остановить», как плита
 * отправки в разговоре; двух кнопок здесь быть не может, потому что состояний ровно два.
 */
@Composable
internal fun RecordScreen(
    recording: Boolean,
    seconds: Int,
    failure: String?,
    onToggle: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        Portal(size = 148.dp, intensity = if (recording) 1f else 0.35f)
        ScreenHeader(
            title = if (recording) recordClock(seconds) else "Звукозапись",
            subtitle = if (recording) "Идёт запись — остановите, когда закончите" else "Запишет Point, ничего больше не нужно",
            horizontalAlignment = Alignment.CenterHorizontally,
        )
        OutcomeBanner(failure, Outcome.FAILED)
        Column(modifier = Modifier.widthIn(max = PortalColumnWidth).fillMaxWidth()) {
            PortalRow(
                title = if (recording) "Остановить" else "Записать",
                subtitle = if (recording) "получится звукозапись" else null,
                onClick = onToggle,
                icon = bubbleIcon("transcribe"),
                accent = bubbleColor("transcribe"),
            )
        }
        TextButton(onClick = onCancel) {
            Text("Отмена", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Счётчик секунд живёт в композиции: он про показ, а не про саму запись. */
@Composable
internal fun RecordTicker(recording: Boolean, onTick: (Int) -> Unit) {
    LaunchedEffect(recording) {
        if (!recording) return@LaunchedEffect
        var n = 0
        while (true) {
            delay(1_000)
            n += 1
            onTick(n)
        }
    }
}

@Preview(name = "Звукозапись · до начала (#246)", showBackground = true, backgroundColor = 0xFF0B0D10)
@Composable
private fun PreviewRecordIdle() = PointTheme(darkTheme = true) {
    RecordScreen(recording = false, seconds = 0, failure = null, onToggle = {}, onCancel = {})
}

@Preview(name = "Звукозапись · идёт (#246)", showBackground = true, backgroundColor = 0xFF0B0D10)
@Composable
private fun PreviewRecordRunning() = PointTheme(darkTheme = true) {
    RecordScreen(recording = true, seconds = 42, failure = null, onToggle = {}, onCancel = {})
}

@Preview(name = "Звукозапись · не вышло (#246)", showBackground = true, backgroundColor = 0xFF0B0D10)
@Composable
private fun PreviewRecordFailed() = PointTheme(darkTheme = true) {
    RecordScreen(
        recording = false,
        seconds = 0,
        failure = "Запись слишком короткая — ничего не записалось",
        onToggle = {},
        onCancel = {},
    )
}
