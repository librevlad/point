package com.point.bot

import org.json.JSONArray
import org.json.JSONObject

/** One inline-keyboard button — a Point bubble rendered as a Telegram tap target (#92). */
data class TgButton(val text: String, val data: String)

/** A message the bot received: text, or a file (photo/document) to download. */
data class TgMessage(
    val chatId: Long,
    val messageId: Long,
    val text: String? = null,
    val fileId: String? = null,
    val mime: String? = null,
    val fileName: String? = null,
)

/** A tap on an inline-keyboard button. */
data class TgCallback(val id: String, val data: String, val chatId: Long, val messageId: Long)

/** One long-poll update — exactly one of [message]/[callback] is set. */
data class TgUpdate(val updateId: Long, val message: TgMessage? = null, val callback: TgCallback? = null)

/** Parse a `getUpdates` response into domain updates; unknown shapes are skipped. */
fun parseUpdates(json: String): List<TgUpdate> {
    val root = runCatching { JSONObject(json) }.getOrNull() ?: return emptyList()
    val result = root.optJSONArray("result") ?: return emptyList()
    return (0 until result.length()).mapNotNull { i ->
        val u = result.optJSONObject(i) ?: return@mapNotNull null
        val id = u.optLong("update_id")
        u.optJSONObject("callback_query")?.let { cb ->
            val msg = cb.optJSONObject("message") ?: return@let null
            return@mapNotNull TgUpdate(id, callback = TgCallback(
                id = cb.optString("id"),
                data = cb.optString("data"),
                chatId = msg.getJSONObject("chat").optLong("id"),
                messageId = msg.optLong("message_id"),
            ))
        }
        u.optJSONObject("message")?.let { m -> TgUpdate(id, message = parseMessage(m)) }
    }
}

private fun parseMessage(m: JSONObject): TgMessage {
    val chatId = m.getJSONObject("chat").optLong("id")
    val messageId = m.optLong("message_id")
    m.optJSONArray("photo")?.let { photos ->
        val largest = (0 until photos.length()).map { photos.getJSONObject(it) }
            .maxByOrNull { it.optLong("file_size") }
        if (largest != null) {
            return TgMessage(chatId, messageId, fileId = largest.optString("file_id"), mime = "image/jpeg")
        }
    }
    m.optJSONObject("document")?.let { doc ->
        return TgMessage(
            chatId, messageId,
            fileId = doc.optString("file_id"),
            mime = doc.optString("mime_type", "application/octet-stream"),
            fileName = doc.optString("file_name").ifBlank { null },
        )
    }
    return TgMessage(chatId, messageId, text = m.optString("text").ifBlank { null })
}

/** Render buttons as a Telegram `reply_markup` JSON (one button per row for legibility),
 *  or null when there are no buttons (Telegram rejects an empty keyboard). */
fun inlineKeyboard(buttons: List<TgButton>): String? {
    if (buttons.isEmpty()) return null
    val rows = JSONArray()
    buttons.forEach { b ->
        rows.put(JSONArray().put(JSONObject().put("text", b.text).put("callback_data", b.data)))
    }
    return JSONObject().put("inline_keyboard", rows).toString()
}
