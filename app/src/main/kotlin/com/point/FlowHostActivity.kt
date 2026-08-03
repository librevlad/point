package com.point

import android.content.Intent
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

/**
 * Общий хост флоу для всех дверей Point (#249).
 *
 * Дверей стало больше одной, и каждая приносила с собой копию одного и того же экрана: список из
 * четырёх десятков колбэков [PointHost] лежал и в `ShareActivity`, и в `ProcessTextActivity` —
 * вместе с обязательной уборкой scratch и перехватом «назад». Третья дверь сделала бы три копии,
 * и разошлись бы они молча: копипаста не падает, она тихо стареет.
 *
 * Здесь живёт всё, что у дверей общее. Дверь отличается ровно одним — [accept]: как разобрать
 * свой intent и что отдать во флоу.
 */
abstract class FlowHostActivity : ComponentActivity() {

    protected val viewModel: FlowViewModel by viewModels()

    /** Разобрать свой intent и отдать объект во флоу. Зовётся при создании и на новом intent. */
    protected abstract fun accept(intent: Intent)

    /**
     * Поднимать ли работу, прерванную смертью процесса (#7). Только у двери «Поделиться»: иконка
     * в лаунчере обязана открывать домашний экран, а не чужую недоделанную работу.
     */
    protected open val restoresJourney: Boolean get() = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (savedInstanceState == null) accept(intent)
        if (restoresJourney) viewModel.restoreJourney()

        onBackPressedDispatcher.addCallback(this) {
            if (!viewModel.onBack()) {
                isEnabled = false
                this@FlowHostActivity.onBackPressedDispatcher.onBackPressed()
            }
        }

        setContent {
            PointTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    val state by viewModel.ui.collectAsStateWithLifecycle()
                    PointHost(
                        state = state,
                        onBubble = viewModel::onBubble,
                        appIconFor = viewModel::appIcon,
                        onPairPc = viewModel::pairPc,
                        onUnpairPc = viewModel::unpairPc,
                        onClosePcSettings = viewModel::closePcSettings,
                        onSubmitInput = viewModel::submitAmendment,
                        onCancelInput = viewModel::cancelInput,
                        onCancelAction = viewModel::cancelAction,
                        onOpenObject = viewModel::openTopObject,
                        onApplyFavorite = viewModel::applyFavorite,
                        onSaveChain = viewModel::saveCurrentChain,
                        onItem = viewModel::onItem,
                        onFound = viewModel::onFound,
                        onJumpTo = viewModel::jumpTo,
                        onSendChat = viewModel::sendChatMessage,
                        onCloseChat = viewModel::closeChat,
                        onBubbleLongPress = viewModel::togglePin,
                        onSaveAiConfig = viewModel::saveAiConfig,
                        onCloseKeySettings = viewModel::closeKeySettings,
                        onToggleUsage = viewModel::setUsageEnabled,
                        onToggleSound = viewModel::setSoundEnabled,
                        onConfirmCloud = viewModel::confirmCloud,
                        onDeclineCloud = viewModel::declineCloud,
                        onPickApp = viewModel::onPickApp,
                        onDismissAppPicker = viewModel::dismissAppPicker,
                        onConfirmPreview = viewModel::confirmPreview,
                        onOpenSelection = viewModel::openSelection,
                        onSelectRegion = viewModel::onSelectRegion,
                        onTakeSelection = viewModel::takeSelection,
                        onCloseSelection = viewModel::closeSelection,
                        onFindQuery = viewModel::onFindQuery,
                        onCloseFind = viewModel::closeFind,
                        onCancelPreview = viewModel::cancelPreview,
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // singleTop: второй объект, прилетевший в живую активити, обязан начать новую работу —
        // без этого intent доставляется и молча теряется.
        accept(intent)
    }

    override fun onDestroy() {
        if (isFinishing) viewModel.endFlow() // обязательная уборка scratch
        super.onDestroy()
    }
}
