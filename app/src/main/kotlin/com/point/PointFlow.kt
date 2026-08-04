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
        onSignIn = viewModel::signIn,
        onCancelSignIn = viewModel::cancelSignIn,
        onContinueAfterSignIn = viewModel::dismissSignIn,
        onRevokeDevice = viewModel::revokeDevice,
        onSignOut = viewModel::signOut,
        onCloseDevices = viewModel::closeDevices,
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
        onOpenKeySettings = { viewModel.openKeySettings() },
        onCheckAiKey = viewModel::checkAiKey,
        onPasteKey = { clipboardText(context) },
        onCloseKeySettings = viewModel::closeKeySettings,
        onToggleUsage = viewModel::setUsageEnabled,
        onToggleSound = viewModel::setSoundEnabled,
        onPickPrivacyLevel = viewModel::setPrivacyLevel,
        onToggleCloud = viewModel::setCloudAllowed,
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

/**
 * Что лежит в буфере обмена — для «Вставить из буфера» на экране ключа (#465).
 *
 * Зовётся ТОЛЬКО из обработчика тапа: Point не заглядывает в чужой буфер, чтобы решить, показывать
 * ли строку. Точка входа одна и живёт рядом с открытием браузера — обе двери про неё не знают и
 * потерять её не могут (тот же довод, которым в своё время родился [PointFlow]).
 */
internal fun clipboardText(context: Context): String? = runCatching {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
    val clip = manager?.primaryClip ?: return@runCatching null
    if (clip.itemCount == 0) null else clip.getItemAt(0).coerceToText(context)?.toString()
}.getOrNull()
