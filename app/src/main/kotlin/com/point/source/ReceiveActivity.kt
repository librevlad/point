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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.point.PulledFileFactory
import com.point.core.flow.DropInbox
import com.point.core.ui.LinkCard
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

    private var link by mutableStateOf<String?>(null)
    private var failure by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PointTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    ReceiveScreen(
                        link = link,
                        failure = failure,
                        onCopy = ::copyLink,
                        onSend = ::sendLink,
                        onCancel = ::finish,
                    )
                }
            }
        }
        lifecycleScope.launch { wait() }
    }

    /**
     * Ящик заводится один на экран, дальше — круги ожидания. Отказ назван словами: молча закрытый
     * экран неотличим от сломанного приложения.
     */
    private suspend fun wait() {
        val box = inbox.open()
        if (box == null) {
            failure = "Ссылку выдать не удалось — нет связи с сервером Point"
            return
        }
        link = box.link
        while (lifecycleScope.isActive) {
            val arrival = inbox.await(box) { name -> files.create(name) }
            if (arrival == null) {
                // Пустой круг — норма: релей держит запрос до половины минуты и отвечает «пусто».
                // Пауза здесь на случай, когда он ответил мгновенно (сеть пропала): без неё экран
                // молотил бы сеть в цикле.
                delay(RETRY_PAUSE_MS)
                continue
            }
            setResult(
                RESULT_OK,
                Intent()
                    .putExtra(EXTRA_PATH, arrival.path)
                    .putExtra(EXTRA_MIME, arrival.mime),
            )
            finish()
            return
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
    }
}

@Composable
private fun ReceiveScreen(
    link: String?,
    failure: String?,
    onCopy: () -> Unit,
    onSend: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
    ) {
        when {
            failure != null -> Text(
                text = failure,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 340.dp),
            )

            link == null -> {
                CircularProgressIndicator(modifier = Modifier.size(28.dp))
                Text(
                    text = "Готовим ссылку…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            else -> {
                LinkCard(
                    url = link,
                    title = "Пусть отправят файл сюда",
                    // Та же честность, что у «Дать ссылку», и в ту же сторону: страницу откроет
                    // любой, у кого ссылка, а файл до приёма полежит на сервере открытым.
                    warning = "Откроет любой, у кого есть ссылка. Живёт сутки. Файл полежит " +
                        "на сервере Point, пока телефон его не заберёт.",
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onCopy) { Text("Скопировать") }
                    TextButton(onClick = onSend) { Text("Отправить ссылку") }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp))
                    Text(
                        text = "Ждём файл…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        TextButton(onClick = onCancel) { Text("Отмена") }
    }
}
