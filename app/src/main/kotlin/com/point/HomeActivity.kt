package com.point

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.point.core.model.tr
import com.point.core.ui.theme.PointTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class HomeActivity : ComponentActivity() {

    private val viewModel: FlowViewModel by viewModels()

    // Источники дом не перечисляет — их спрашивает экран выбора (#1232): подпись двери
    // берётся из WHAT_YOU_HAVE, и опрашивать доступность источников на старте незачем.
    private fun newObject() {
        startActivity(android.content.Intent(this, com.point.source.SourcePickerActivity::class.java))
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        pullIfKnocked(intent)
    }

    /** Открыто со стука — просьба компьютера забирается сразу, без второго тапа (#1091). */
    private fun pullIfKnocked(intent: android.content.Intent?) {
        if (intent?.getBooleanExtra(com.point.knock.Knock.FROM_KNOCK, false) == true) {
            intent.removeExtra(com.point.knock.Knock.FROM_KNOCK)
            viewModel.pullFromPc()
        }
    }

    private fun example() {
        viewModel.openExample(exampleObject(packageName))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel.loadRecent()
        pullIfKnocked(intent)

        // Пока дом открыт, телефон сам замечает, что оставил компьютер (#1400). Прежде он
        // спрашивал только на входе в дом: просьба, пришедшая, пока человек смотрит на экран,
        // ждала, пока он выйдет и войдёт снова. Спрашивает он так же редко, как вход в дом, а в
        // фоне Point по-прежнему ничего не делает.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) {
                    kotlinx.coroutines.delay(OUTBOX_THROTTLE_MS)
                    if (!viewModel.hasFlow()) viewModel.noticeFromPc()
                }
            }
        }

        onBackPressedDispatcher.addCallback(this) {
            when {
                viewModel.onBack() -> Unit

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
                com.point.core.ui.PointScreen {
                    val state by viewModel.ui.collectAsStateWithLifecycle()
                    if (state.frame == null && state.busy == null && state.message == null &&
                        state.keyScreen == null && state.devicesScreen == null && state.signIn == null
                    ) {
                        val recent by viewModel.recent.collectAsStateWithLifecycle()
                        val crash by viewModel.crashReport.collectAsStateWithLifecycle()
                        val fromPcCount by viewModel.fromPcCount.collectAsStateWithLifecycle()

                        // Point не заглядывает в буфер сам (#677, решение владельца
                        // «только по жесту»): Android палил чтение тостом «Point pasted
                        // from your clipboard», а человек ничего не просил.
                        HomeScreen(
                            recent = recent,
                            onOpen = viewModel::openFromHistory,

                            onSettings = viewModel::openKeySettings,
                            onNewObject = ::newObject,
                            onExample = ::example,
                            onRemove = { viewModel.removeFromHistory(it.id) },
                            onClear = viewModel::clearHistory,
                            crashReport = crash,
                            onSendCrash = ::shareCrashReport,
                            onDismissCrash = viewModel::dismissCrashReport,
                            fromPcCount = fromPcCount,
                            onPullFromPc = viewModel::pullFromPc,
                            onHideFromPc = viewModel::hideFromPc,

                            aiKeySet = state.aiKeySet,
                        )

                        // Point сам предлагает разрешить уведомления, когда в круге есть
                        // компьютер (#1449): на экране «с чего начать» тоже.
                        KnockOffer(
                            pcInCircle = state.pcInCircle,
                            modifier = androidx.compose.ui.Modifier
                                .align(androidx.compose.ui.Alignment.BottomCenter)
                                .systemBarsPadding()
                                .widthIn(max = com.point.core.ui.PortalColumnWidth)
                                .padding(16.dp),
                        )
                    } else {
                        PointFlow(
                            state = state,
                            viewModel = viewModel,

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
        viewModel.returnedToPoint()
        if (!viewModel.hasFlow()) viewModel.loadRecent()

        viewModel.resumeSignIn()
    }

    // Буфер обмена Point сам не читает (#677/#638, решение владельца «только по
    // жесту»): чтение при каждом фокусе окна показывало человеку системный тост
    // «Point pasted from your clipboard» и выкладывало чужое содержимое на экран.
    // Буфер остаётся полноценным источником — через «Новый объект → Буфер обмена».

    private fun shareCrashReport(report: String) {
        val send = android.content.Intent(android.content.Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(android.content.Intent.EXTRA_SUBJECT, tr("Point - отчёт о падении"))
            .putExtra(android.content.Intent.EXTRA_TEXT, report)
        startActivity(android.content.Intent.createChooser(send, tr("Отправить отчёт")))
        viewModel.dismissCrashReport()
    }

}

