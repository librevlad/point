package com.point.bot

import java.io.File

/**
 * The Telegram side effects behind a seam (#92) — the engine stays JVM-testable with a
 * fake, HTTP lives only in [HttpTelegramApi]. Same discipline as the desktop's contracts.
 */
interface TelegramApi {
    suspend fun sendMessage(chatId: Long, text: String, keyboard: String? = null)
    suspend fun sendDocument(chatId: Long, file: File, caption: String? = null)

    /** Download a Telegram file (by file_id) into [target]; false on any failure. */
    suspend fun downloadFile(fileId: String, target: File): Boolean

    /** Acknowledge a callback tap so Telegram stops the button's spinner. */
    suspend fun answerCallback(callbackId: String)
}
