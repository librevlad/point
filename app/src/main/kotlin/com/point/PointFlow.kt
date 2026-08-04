package com.point

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext

/**
 * Флоу, подключённый к модели, — один на все двери Point (#114).
 *
 * У [PointHost] четыре десятка колбэков, и до сих пор этот список лежал в каждой двери своей
 * копией: в [FlowHostActivity] (Поделиться, текст, печать) и в [HomeActivity] (иконка в лаунчере).
 * Копия не падает — она тихо стареет: `onOpenUrl` доехал до первой двери и не доехал до второй, и
 * кнопка «Взять ключ» в настройках, открытых шестерёнкой с домашнего экрана, не делала **ничего**.
 * Поймать это было нечем — колбэки дверей не проверял ни один тест.
 *
 * Поэтому список живёт здесь, в одном месте, и потерять из него строку можно только вместе со
 * всеми дверьми сразу. Дверь отличается ровно одним — [onLeave]: куда ведёт выход.
 */
@Composable
fun PointFlow(
    state: FlowUiState,
    viewModel: FlowViewModel,
    /** Уйти с экрана-сообщения, за которым нет объекта: у каждой двери своё «откуда пришли». */
    onLeave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
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
        onCancelChat = viewModel::cancelChatMessage,
        onBubbleLongPress = viewModel::togglePin,
        onSaveAiConfig = viewModel::saveAiConfig,
        onOpenKeySettings = viewModel::openKeySettings,
        onCloseKeySettings = viewModel::closeKeySettings,
        onToggleUsage = viewModel::setUsageEnabled,
        onToggleSound = viewModel::setSoundEnabled,
        onPickPrivacyLevel = viewModel::setPrivacyLevel,
        onConfirmCloud = viewModel::confirmCloud,
        onDeclineCloud = viewModel::declineCloud,
        onPickApp = viewModel::onPickApp,
        onDismissAppPicker = viewModel::dismissAppPicker,
        onConfirmPreview = viewModel::confirmPreview,
        onCancelPreview = viewModel::cancelPreview,
        onOpenSelection = viewModel::openSelection,
        onSelectRegion = viewModel::onSelectRegion,
        onTakeSelection = viewModel::takeSelection,
        onCloseSelection = viewModel::closeSelection,
        onFindQuery = viewModel::onFindQuery,
        onCloseFind = viewModel::closeFind,
        onOpenUrl = { url -> openInBrowser(context, url) },
        onDismissMessage = onLeave,
        modifier = modifier,
    )
}

/**
 * Открыть страницу браузером телефона (#403).
 *
 * Своего окна для чужих сайтов у Point нет и не будет: он не браузер (продуктовый фильтр). Отказ
 * проглатывается намеренно — телефон без браузера редкость, а падать из-за неё нельзя.
 */
internal fun openInBrowser(context: Context, url: String) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
