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

    /** The PC's current clipboard. [ClipPull.Empty] and [ClipPull.Unreachable] are kept distinct so a
     *  LAN→relay fallback only fires on unreachability, not on a legitimately empty PC clipboard. */
    suspend fun pull(pairing: PcPairing): ClipPull
}

/** The outcome of a clipboard [PcClipboardSync.pull]. */
sealed interface ClipPull {
    /** The PC's clipboard, read successfully. */
    data class Got(val payload: ClipboardPayload) : ClipPull

    /** The PC was reached, but its clipboard is empty — do NOT fall back to another transport. */
    data object Empty : ClipPull

    /** The PC couldn't be reached on this transport — a caller may try the relay next. */
    data object Unreachable : ClipPull
}

/**
 * The relay clipboard protocol (#161 «общий буфер» через релей). The LAN hop is request/response, but
 * the relay is a one-way blind mailbox, so the shared clipboard rides two token-derived mailboxes:
 * the phone deposits pushes and pull-requests into [TO_PC]; the desktop, long-polling [TO_PC], sets
 * its clipboard on a push and answers a pull-request by depositing the PC's clipboard into [TO_PHONE],
 * which the phone then polls. Every message is a sealed [PcFrame] — the relay only sees ciphertext.
 */
object ClipRelay {
    const val TO_PC = "clip-to-pc"
    const val TO_PHONE = "clip-to-phone"
    const val KIND = "clip"
    const val PUSH = "push"
    const val PULL = "pull"
    const val REPLY = "reply"
    private const val MIME = "mime"
    private const val NAME = "name"

    internal fun metaKeys() = Triple(KIND, MIME, NAME)
}

/** A decoded clipboard relay message: its [kind] and the [payload] (null for a pull request or an
 *  empty reply — i.e. whenever no content crossed). */
data class ClipFrame(val kind: String, val payload: ClipboardPayload?)

/** Seal-ready bytes for one clip message. A null [payload] means «no content» (a pull request, or an
 *  empty-clipboard reply): no mime meta is written, so [decodeClipFrame] yields a null payload. */
fun encodeClipFrame(kind: String, payload: ClipboardPayload?): ByteArray {
    val (kKey, mKey, nKey) = ClipRelay.metaKeys()
    val meta = buildMap {
        put(kKey, kind)
        payload?.let { put(mKey, it.mime); put(nKey, it.name) }
    }
    return encodePcFrame(meta, payload?.bytes ?: ByteArray(0))
}

fun decodeClipFrame(blob: ByteArray): ClipFrame {
    val (kKey, mKey, nKey) = ClipRelay.metaKeys()
    val frame = decodePcFrame(blob)
    val mime = frame.meta[mKey]
    val payload = if (mime == null) null else ClipboardPayload(mime, frame.meta[nKey] ?: "", frame.bytes)
    return ClipFrame(frame.meta[kKey] ?: "", payload)
}
