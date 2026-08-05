package com.point.source

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.point.PulledFileFactory
import com.point.core.flow.DropInbox
import com.point.core.flow.DropInboxBox
import com.point.core.flow.DropWait
import com.point.core.flow.receiveWaitStatus
import com.point.core.ui.LinkCard
import com.point.core.ui.Outcome
import com.point.core.ui.OutcomeBanner
import com.point.core.ui.Portal
import com.point.core.ui.PortalColumnWidth
import com.point.core.ui.PortalRow
import com.point.core.ui.ScreenHeader
import com.point.core.ui.ThinkingDot
import com.point.core.ui.bubbleColor
import com.point.core.ui.bubbleIcon
import com.point.core.ui.theme.PointTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * «Принять файл» (#388) — экран ожидания: ссылка, код и честно названная цена.
 *
 * Человек показывает код тому, кто рядом, или отправляет ссылку тому, кто далеко; тот открывает её
 * любым браузером и отправляет файл. Как только файл приехал, экран исчезает, и файл уходит в
 * обычную дверь Point — становится объектом, с которым можно работать. Никаких цепочек: что с ним
 * делать, человек решает потом сам.
 *
 * Ожидание — только пока экран открыт. Фонового приёма нет и не задумано: Point не заводит
 * службу, которая слушает сеть, пока им не пользуются.
 */
@AndroidEntryPoint
class ReceiveActivity : ComponentActivity() {

    @Inject lateinit var inbox: DropInbox
    @Inject lateinit var files: PulledFileFactory

    private var box: DropInboxBox? = null
    private var link by mutableStateOf<String?>(null)
    private var failure by mutableStateOf<String?>(null)
    /** Сколько кругов ожидания подряд закончились отказом сети — из этого сложены слова на экране. */
    private var failures by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Показанная человеку ссылка обязана пережить поворот телефона (#114). Раньше поворот был
        // новым onCreate, новым ящиком и новой ссылкой — а та, что человек уже показал соседу или
        // отправил, молча умирала. Ящик живёт на сервере сутки, поэтому пересозданный экран
        // возвращается к нему по сохранённому адресу вместо того, чтобы заводить второй.
        box = restoredBox(savedInstanceState?.getString(STATE_ID), savedInstanceState?.getString(STATE_LINK))
        link = box?.link
        setContent {
            PointTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    ReceiveScreen(
                        link = link,
                        failure = failure,
                        status = receiveWaitStatus(failures),
                        onCopy = ::copyLink,
                        onSend = ::sendLink,
                        onCancel = ::finish,
                    )
                }
            }
        }
        lifecycleScope.launch { wait() }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        box?.let {
            outState.putString(STATE_ID, it.id)
            outState.putString(STATE_LINK, it.link)
        }
    }

    /**
     * Ящик заводится один на экран, дальше — круги ожидания. Отказ назван словами: молча закрытый
     * экран неотличим от сломанного приложения.
     */
    private suspend fun wait() {
        // Прежний ящик, если экран пересоздали, — иначе новый. Второй раз ящик не заводится:
        // именно это раньше и убивало показанную человеку ссылку при повороте.
        val opened = box ?: inbox.open()?.also { box = it }
        if (opened == null) {
            failure = "Ссылку выдать не удалось — нет связи с сервером Point"
            return
        }
        link = opened.link
        while (lifecycleScope.isActive) {
            when (val outcome = inbox.await(opened) { name -> files.create(name) }) {
                // Пустой круг — норма: релей держит запрос до половины минуты и отвечает «пусто».
                // Пауза здесь на случай, когда он ответил мгновенно: без неё экран молотил бы сеть.
                is DropWait.Empty -> {
                    failures = 0
                    delay(RETRY_PAUSE_MS)
                }
                // Сеть упала. Молчать нельзя — иначе «Ждём файл…» висит вечно; закрываться тоже:
                // связь возвращается, а ящик и ссылка живы. Говорим словами и ждём реже.
                is DropWait.Failed -> {
                    failures++
                    delay(FAIL_PAUSE_MS)
                }
                is DropWait.Arrived -> {
                    setResult(
                        RESULT_OK,
                        Intent()
                            .putExtra(EXTRA_PATH, outcome.arrival.path)
                            .putExtra(EXTRA_MIME, outcome.arrival.mime),
                    )
                    finish()
                    return
                }
            }
        }
    }

    private fun copyLink() {
        val text = link ?: return
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        cm.setPrimaryClip(ClipData.newPlainText("Ссылка для отправки файла", text))
    }

    private fun sendLink() {
        val text = link ?: return
        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text),
                "Отправить ссылку",
            ),
        )
    }

    companion object {
        const val EXTRA_PATH = "com.point.receive.PATH"
        const val EXTRA_MIME = "com.point.receive.MIME"
        private const val RETRY_PAUSE_MS = 1_000L
        /** Сеть упала — стучаться раз в секунду бессмысленно и дорого для батареи. */
        private const val FAIL_PAUSE_MS = 3_000L
        private const val STATE_ID = "com.point.receive.BOX_ID"
        private const val STATE_LINK = "com.point.receive.BOX_LINK"
    }
}

/**
 * Ящик, переживший пересоздание экрана, — или `null`, если сохранять было нечего (#114).
 *
 * Отдельной функцией, а не строкой внутри `onCreate`: решение «продолжаем прежний ящик или заводим
 * новый» и есть то, из-за чего поворот убивал показанную человеку ссылку.
 */
internal fun restoredBox(id: String?, link: String?): DropInboxBox? =
    if (!id.isNullOrBlank() && !link.isNullOrBlank()) DropInboxBox(id, link) else null

/**
 * Экран ожидания — в языке портала (#114), и целиком рисуется в `@Preview`: он ничего не знает про
 * Activity, ящик и сеть, ему дают ссылку либо отказ.
 *
 * Что было не так. Ждали двумя крутилками Material — а Point ждёт порталом и пульсом
 * (MOTION.md принцип №3: импульс, а не крутилка), и человек, видевший портал на каждом долгом
 * действии, здесь встречал чужой знак. Отказ был обычной строкой текста вместо карточки исхода,
 * которой Point отвечает везде. Действия со ссылкой были двумя голыми текстовыми кнопками, хотя
 * «скопировать» и «отправить» — такие же действия, как на экране объекта, и выглядеть должны так
 * же. И экран не называл себя вовсе: пока ссылка готовилась, на нём не было ни слова о том, куда
 * человек попал.
 */
@Composable
private fun ReceiveScreen(
    link: String?,
    failure: String?,
    /** Слова под ссылкой: ждём — или сети нет, и это сказано вслух (#114). */
    status: String,
    onCopy: () -> Unit,
    onSend: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
    ) {
        when {
            // Отказ — той же карточкой исхода, что на экране объекта: знак «✕» тёплым концом
            // фирменного градиента на поверхности портала.
            failure != null -> OutcomeBanner(failure, Outcome.FAILED)

            link == null -> {
                Portal(size = 148.dp)
                ScreenHeader(
                    title = "Принять файл",
                    subtitle = "Готовим ссылку…",
                    horizontalAlignment = Alignment.CenterHorizontally,
                )
            }

            else -> {
                ScreenHeader(
                    title = "Принять файл",
                    subtitle = "Покажите код рядом или отправьте ссылку тому, кто далеко",
                    horizontalAlignment = Alignment.CenterHorizontally,
                )
                LinkCard(
                    url = link,
                    title = "Пусть отправят файл сюда",
                    // Та же честность, что у «Дать ссылку», и в ту же сторону: страницу откроет
                    // любой, у кого ссылка, а файл до приёма полежит на сервере открытым.
                    warning = "Откроет любой, у кого есть ссылка. Живёт сутки. Файл полежит " +
                        "на сервере Point, пока телефон его не заберёт.",
                )
                Column(
                    modifier = Modifier.widthIn(max = PortalColumnWidth).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(11.dp),
                ) {
                    PortalRow(
                        title = "Скопировать ссылку",
                        onClick = onCopy,
                        icon = bubbleIcon("copy"),
                        accent = bubbleColor("copy"),
                        appearIndex = 0,
                    )
                    PortalRow(
                        title = "Отправить ссылку",
                        onClick = onSend,
                        icon = bubbleIcon("share"),
                        accent = bubbleColor("share"),
                        appearIndex = 1,
                    )
                }
                // Тем же пульсом, каким Point думает над объектом: ждём — значит работаем.
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    ThinkingDot()
                    Text(
                        text = status,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.widthIn(max = 320.dp),
                    )
                }
            }
        }
        TextButton(onClick = onCancel) {
            Text("Отмена", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Preview(name = "Принять файл · ссылка готова (#114)", showBackground = true, backgroundColor = 0xFF0B0D10)
@Composable
private fun PreviewReceiveWaiting() = PointTheme(darkTheme = true) {
    ReceiveScreen(
        link = "https://point.leerio.app/u/2f8c1b0a4e6d9c3f5a7b1e2d4c6f8a0b1c3d5e7f",
        failure = null,
        status = receiveWaitStatus(0),
        onCopy = {},
        onSend = {},
        onCancel = {},
    )
}

@Preview(name = "Принять файл · готовим ссылку (#114)", showBackground = true, backgroundColor = 0xFF0B0D10)
@Composable
private fun PreviewReceivePreparing() = PointTheme(darkTheme = true) {
    // Ждём порталом, а не крутилкой, — и экран наконец называет себя.
    ReceiveScreen(link = null, failure = null, status = receiveWaitStatus(0), onCopy = {}, onSend = {}, onCancel = {})
}

@Preview(name = "Принять файл · не получилось (#114)", showBackground = true, backgroundColor = 0xFF0B0D10)
@Composable
private fun PreviewReceiveFailed() = PointTheme(darkTheme = true) {
    ReceiveScreen(
        link = null,
        failure = "Ссылку выдать не удалось — нет связи с сервером Point",
        status = receiveWaitStatus(0),
        onCopy = {},
        onSend = {},
        onCancel = {},
    )
}
