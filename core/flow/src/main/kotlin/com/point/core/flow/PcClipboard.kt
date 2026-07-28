package com.point.core.flow

/**
 * Shared clipboard with the paired PC (#161 — «общий буфер, как в Apple»). Android forbids a
 * background app from touching the clipboard, so the sync is triggered from a Quick Settings tile: a
 * momentary foreground activity reads/writes the phone clipboard and calls this. The tile [push]es
 * when the phone's clipboard changed since the last sync, otherwise [pull]s the PC's — so a copy on
 * either device lands on the other without clipboard-conflict versioning.
 */
interface PcClipboardSync {
    /** Send the phone's clipboard [text] to the PC's system clipboard. True on success. */
    suspend fun push(pairing: PcPairing, text: String): Boolean

    /** The PC's current clipboard text, or null if it couldn't be read. */
    suspend fun pull(pairing: PcPairing): String?
}
