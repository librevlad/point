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
import com.point.core.flow.DropOpen
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

@AndroidEntryPoint
class ReceiveActivity : ComponentActivity() {

    @Inject lateinit var inbox: DropInbox
    @Inject lateinit var files: PulledFileFactory

    private var box: DropInboxBox? = null
    private var link by mutableStateOf<String?>(null)
    private var failure by mutableStateOf<String?>(null)

    private var failures by mutableIntStateOf(0)

    /** Файл дошёл — ящик закроет тот, кто подтвердит приём, вместе с `ack` (#729). */
    private var arrived = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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
                        onSignIn = ::openSignIn.takeIf {
                            failure == com.point.core.flow.NOT_IN_ACCOUNT_TEXT
                        },
                    )
                }
            }
        }
        lifecycleScope.launch { wait() }
    }

    /**
     * Человек ушёл, не дождавшись, — дверь закрывается за ним (#729).
     *
     * Прежде ящик убирала только суточная уборка: пять открытий этого экрана за день
     * выбирали весь предел, и приём переставал работать до утра. Поворот экрана не
     * закрывает: там ящик переживает пересоздание вместе с состоянием.
     */
    override fun onDestroy() {
        val open = box
        if (open != null && !arrived && !isChangingConfigurations) {
            box = null
            com.point.core.flow.closeInBackground(inbox, open)
        }
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        box?.let {
            outState.putString(STATE_ID, it.id)
            outState.putString(STATE_LINK, it.link)
        }
    }

    private suspend fun wait() {

        // Три разных положения — три разных текста (#729): «ссылок слишком много»,
        // «устройство не в аккаунте» и «связи нет» звучали одинаково, и человек шёл
        // проверять Wi-Fi там, где чинить нужно было другое.
        val opened = box ?: when (val outcome = inbox.open()) {
            is DropOpen.Opened -> outcome.box.also { box = it }
            is DropOpen.Refused -> {
                failure = outcome.reason
                return
            }
        }
        link = opened.link
        while (lifecycleScope.isActive) {
            when (val outcome = inbox.await(opened) { name -> files.create(name) }) {

                is DropWait.Empty -> {
                    failures = 0
                    delay(RETRY_PAUSE_MS)
                }

                is DropWait.Failed -> {
                    failures++
                    delay(FAIL_PAUSE_MS)
                }
                is DropWait.Arrived -> {

                    // Файл на диске, но объекта из него ещё нет: подтверждение приёма уходит
                    // позже, у того, кто объект создаст (`ReceiveFileSource.read`). Пока не
                    // создан — файл обязан остаться на сервере: прислал его чужой человек.
                    setResult(
                        RESULT_OK,
                        Intent()
                            .putExtra(EXTRA_PATH, outcome.arrival.path)
                            .putExtra(EXTRA_MIME, outcome.arrival.mime)
                            .putExtra(EXTRA_BOX, opened.id)
                            .putExtra(EXTRA_FILE_ID, outcome.arrival.fileId),
                    )
                    arrived = true
                    finish()
                    return
                }
            }
        }
    }

    /** Дверь, которая чинит причину: вход живёт своим экраном, туда и ведём. */
    private fun openSignIn() {
        runCatching {
            startActivity(Intent(this, Class.forName("com.point.SignedInActivity")))
        }
        finish()
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
        const val EXTRA_BOX = "com.point.receive.BOX"
        const val EXTRA_FILE_ID = "com.point.receive.FILE_ID"
        private const val RETRY_PAUSE_MS = 1_000L

        private const val FAIL_PAUSE_MS = 3_000L
        private const val STATE_ID = "com.point.receive.BOX_ID"
        private const val STATE_LINK = "com.point.receive.BOX_LINK"
    }
}

internal fun restoredBox(id: String?, link: String?): DropInboxBox? =
    if (!id.isNullOrBlank() && !link.isNullOrBlank()) DropInboxBox(id, link) else null

@Composable
private fun ReceiveScreen(
    link: String?,
    failure: String?,

    status: String,
    onCopy: () -> Unit,
    onSend: () -> Unit,
    onCancel: () -> Unit,
    onSignIn: (() -> Unit)? = null,
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

            // Отказ называл причину и оставлял человека с ней наедине: пустой экран, красная
            // плашка и «Отмена». Причина, из-за которой не открылось, теперь стоит под
            // именем экрана, а дверь, которая её чинит, — тут же (#897).
            failure != null -> {
                ScreenHeader(
                    title = "Приём не открылся",
                    subtitle = failure,
                    horizontalAlignment = Alignment.CenterHorizontally,
                )
                if (onSignIn != null) {
                    Column(
                        modifier = Modifier.widthIn(max = PortalColumnWidth).fillMaxWidth(),
                    ) {
                        PortalRow(
                            title = "Войти",
                            subtitle = "Ссылку приёма выдаёт сервер — для этого устройство должно быть в вашем аккаунте.",
                            onClick = onSignIn,
                            icon = bubbleIcon("account"),
                            accent = bubbleColor("account"),
                            primary = true,
                            chevron = false,
                            subtitleMaxLines = 3,
                        )
                    }
                }
            }

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
