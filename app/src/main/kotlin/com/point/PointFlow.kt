package com.point

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext

@Composable
fun PointFlow(
    state: FlowUiState,
    viewModel: FlowViewModel,

    onLeave: () -> Unit,

    leaveLabel: String = LEAVE_TO_HOME,
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
        onDeleteAccount = viewModel::deleteAccount,
        onOpenDevices = viewModel::openDevices,
        onCloseDevices = viewModel::closeDevices,
        onSubmitInput = viewModel::submitAmendment,
        onCancelInput = viewModel::cancelInput,
        onCancelAction = viewModel::cancelAction,
        onOpenObject = viewModel::openTopObject,
        onItem = viewModel::onItem,
        onFound = viewModel::onFound,
        onJumpTo = viewModel::jumpTo,
        onSendChat = viewModel::sendChatMessage,
        onCloseChat = viewModel::closeChat,
        onCancelChat = viewModel::cancelChatMessage,
        onTakeChatAnswer = viewModel::takeChatAnswer,
        onRunChatOffer = viewModel::runChatOffer,
        onSaveAiKey = viewModel::saveAiKey,
        onOpenKeySettings = { viewModel.openKeySettings() },
        onCheckAiKey = viewModel::checkAiKey,
        onCheckAllAiKeys = viewModel::checkAllAiKeys,
        onPasteKey = { clipboardText(context) },
        onForgetAiKey = viewModel::forgetAiKey,
        onCloseKeySettings = viewModel::closeKeySettings,
        onToggleSound = viewModel::setSoundEnabled,
        onPickPrivacyLevel = viewModel::setPrivacyLevel,
        onToggleCloud = viewModel::setCloudAllowed,
        onToggleYolo = viewModel::setYoloEnabled,
        onForgetAll = viewModel::clearHistory,

        // Плитка — точка входа, о которой человек мог не знать (#821). Её наличие знает
        // система, а не Graph: спрашиваем здесь, а не тащим Context в модель.
        tileAdded = com.point.source.shadeTileKnown(context),
        onConfirmCloud = viewModel::confirmCloud,
        onDeclineCloud = viewModel::declineCloud,
        onPickApp = viewModel::onPickApp,
        onDismissAppPicker = viewModel::dismissAppPicker,
        onConfirmPreview = viewModel::confirmPreview,
        onCancelPreview = viewModel::cancelPreview,
        onOpenSelection = viewModel::openSelection,
        onSelectRegion = viewModel::onSelectRegion,
        onClearFocus = viewModel::clearFocus,
        onTakeSelection = viewModel::takeSelection,
        onFocusSelection = viewModel::focusOnSelection,
        onCloseSelection = viewModel::closeSelection,
        onFindQuery = viewModel::onFindQuery,
        onCloseFind = viewModel::closeFind,
        onOpenUrl = { url -> openInBrowser(context, url) },
        onDismissMessage = onLeave,
        leaveLabel = leaveLabel,
        modifier = modifier,
    )
}

internal fun openInBrowser(context: Context, url: String) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

internal fun clipboardText(context: Context): String? = runCatching {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
    val clip = manager?.primaryClip ?: return@runCatching null
    if (clip.itemCount == 0) null else clip.getItemAt(0).coerceToText(context)?.toString()
}.getOrNull()
