package com.point.core.flow

/**
 * One clipboard payload crossing between phone and PC (#161 «общий буфер»): its [mime], a display
 * [name] (for files), and raw [bytes]. Text is `text/plain` with UTF-8 bytes; a screenshot is
 * `image/png`; a copied file is its own mime. Not a data class — [bytes] equality would be reference
 * based anyway; callers compare via [signature].
 */
class ClipboardPayload(val mime: String, val name: String, val bytes: ByteArray) {
    val isText: Boolean get() = mime.startsWith("text/")
    val isImage: Boolean get() = mime.startsWith("image/")

    /** The text, when this payload is text. */
    fun text(): String = String(bytes, Charsets.UTF_8)

    /** A cheap identity for «did the clipboard change since last sync» (mime + size + content hash). */
    fun signature(): String = "$mime:${bytes.size}:${bytes.contentHashCode()}"

    companion object {
        fun ofText(text: String) = ClipboardPayload("text/plain", "", text.toByteArray(Charsets.UTF_8))
    }
}

/**
 * Shared clipboard with the paired PC (#161 — «общий буфер, как в Apple»). Android forbids a
 * background app from touching the clipboard, so the sync is triggered from a Quick Settings tile: a
 * momentary foreground activity reads/writes the phone clipboard and calls this. The tile [push]es
 * when the phone's clipboard changed since the last sync, otherwise [pull]s the PC's — so a copy on
 * either device (text, image, or file) lands on the other without clipboard-conflict versioning.
 */
interface PcClipboardSync {
    /** Send the phone's clipboard [payload] to the PC's system clipboard. True on success. */
    suspend fun push(pairing: PcPairing, payload: ClipboardPayload): Boolean

    /** The PC's current clipboard, or null if it couldn't be read / is empty. */
    suspend fun pull(pairing: PcPairing): ClipboardPayload?
}
