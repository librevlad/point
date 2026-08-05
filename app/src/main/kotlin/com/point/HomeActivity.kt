package com.point

import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.point.core.ui.theme.PointTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * Point's launcher home. Shows recent objects (History); tapping one re-opens it
 * into the flow. While a flow is active it hosts the same [PointHost] as Share.
 */
@AndroidEntryPoint
class HomeActivity : ComponentActivity() {

    private val viewModel: FlowViewModel by viewModels()

    /**
     * Источники объекта (#456) — нужны экрану только именами: подпись двери «Новый объект»
     * перечисляет их поимённо, чтобы камера, голос, буфер и место перестали быть догадкой.
     *
     * Фильтр `isAvailable` — тот же, что на экране выбора: назвать источник, которого на этом
     * телефоне нет, значит соврать. Он спрашивает `PackageManager`, поэтому считается один раз.
     */
    @javax.inject.Inject lateinit var sources: Set<@JvmSuppressWildcards com.point.source.ObjectSource>

    private val sourceLabels: List<String> by lazy {
        sources.filter { it.isAvailable(this) }.map { it.label }.sorted()
    }

    /**
     * Дверь «Новый объект» (#456): пять источников за одним тапом. Раньше вход был один — плитка
     * шторки, которую надо было самому найти в редакторе плиток, — и «Принять файл» единственный
     * из пяти имел здесь свою иконку. Теперь он стоит среди своих, и отдельный путь ему не нужен.
     */
    private fun newObject() {
        startActivity(android.content.Intent(this, com.point.source.SourcePickerActivity::class.java))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel.loadRecent()

        onBackPressedDispatcher.addCallback(this) {
            when {
                viewModel.onBack() -> Unit
                // Сообщение без объекта («Ключ AI сохранён», «Объект недоступен», чужой QR) —
                // не тупик: «назад» с него возвращает на «Недавнее». Раньше оно уходило системе,
                // и Point закрывался прямо после удачного сохранения ключа (#114).
                viewModel.dismissMessage() -> viewModel.loadRecent()
                viewModel.hasFlow() -> { viewModel.endFlow(); viewModel.loadRecent() }
                else -> {
                    isEnabled = false
                    this@HomeActivity.onBackPressedDispatcher.onBackPressed()
                }
            }
        }

        setContent {
            PointTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val state by viewModel.ui.collectAsStateWithLifecycle()
                    if (state.frame == null && state.busy == null && state.message == null &&
                        state.keyScreen == null && state.devicesScreen == null && state.signIn == null
                    ) {
                        val recent by viewModel.recent.collectAsStateWithLifecycle()
                        val clipboard by viewModel.clipboard.collectAsStateWithLifecycle()
                        val crash by viewModel.crashReport.collectAsStateWithLifecycle()
                        val basketCount by viewModel.basketCount.collectAsStateWithLifecycle()
                        val fromPcCount by viewModel.fromPcCount.collectAsStateWithLifecycle()
                        // Re-offer the clipboard each time Home comes back on screen: after Back
                        // out of a restored flow the focus edge has already passed (#111).
                        androidx.compose.runtime.LaunchedEffect(Unit) {
                            viewModel.refreshClipboard(::readClipboardText)
                        }
                        HomeScreen(
                            recent = recent,
                            onOpen = viewModel::openFromHistory,
                            onSettings = viewModel::openKeySettings,
                            onPc = viewModel::openDevices,
                            onNewObject = ::newObject,
                            sourceLabels = sourceLabels,
                            onClear = viewModel::clearHistory,
                            clipboard = clipboard,
                            onUseClipboard = ::useClipboard,
                            onDismissClipboard = viewModel::dismissClipboard,
                            crashReport = crash,
                            onSendCrash = ::shareCrashReport,
                            onDismissCrash = viewModel::dismissCrashReport,
                            basketCount = basketCount,
                            onOpenBasket = viewModel::openBasket,
                            onClearBasket = viewModel::clearBasket,
                            fromPcCount = fromPcCount,
                            onPullFromPc = viewModel::pullFromPc,
                            onHideFromPc = viewModel::hideFromPc,
                            // Пока ключа нет — «Недавнее» само зовёт его подключить (#465).
                            aiKeySet = state.aiKeySet,
                        )
                    } else {
                        PointFlow(
                            state = state,
                            viewModel = viewModel,
                            // Домашняя дверь: «откуда пришли» — «Недавнее». Выход с
                            // экрана-сообщения ведёт туда, а не из Point (#114), — и только здесь
                            // надпись «← Недавнее» говорит правду (#531).
                            onLeave = { onBackPressedDispatcher.onBackPressed() },
                            leaveLabel = LEAVE_TO_HOME,
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!viewModel.hasFlow()) viewModel.loadRecent()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // The clipboard is readable only with window focus (Android 10+), which lands AFTER
        // onResume — reading here is what makes the "act on copied text" offer appear (#72).
        if (hasFocus && !viewModel.hasFlow()) {
            val text = readClipboardText()
            viewModel.offerClipboard(text)
            // #111: on some devices the clipboard is not yet served at the focus edge —
            // one delayed retry closes that race without ever polling in background.
            if (text.isNullOrBlank()) {
                window.decorView.postDelayed({
                    if (hasWindowFocus() && !viewModel.hasFlow()) {
                        viewModel.offerClipboard(readClipboardText())
                    }
                }, CLIPBOARD_RETRY_MS)
            }
        }
    }

    /** The current clipboard text, or null. Only ever called while Point is in the foreground. */
    private fun readClipboardText(): String? {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
        val clip = cm.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        return clip.getItemAt(0).coerceToText(this)?.toString()
    }

    /** #11: the report leaves the device only through the user's own share choice. */
    private fun shareCrashReport(report: String) {
        val send = android.content.Intent(android.content.Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(android.content.Intent.EXTRA_SUBJECT, "Point - отчёт о падении")
            .putExtra(android.content.Intent.EXTRA_TEXT, report)
        startActivity(android.content.Intent.createChooser(send, "Отправить отчёт"))
        viewModel.dismissCrashReport()
    }

    private fun useClipboard(text: String) {
        viewModel.dismissClipboard()
        // Скопированное называется своими первыми словами (#533), как любой другой текст,
        // и убирается вместе с ним в конце флоу.
        viewModel.onSharedText(text)
    }
}

private const val CLIPBOARD_RETRY_MS = 300L
