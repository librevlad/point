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
    /** Send the phone's clipboard [payload] to the PC's system clipboard. */
    suspend fun push(pairing: PcPairing, payload: ClipboardPayload): ClipPush

    /** The PC's current clipboard. [ClipPull.Empty] and [ClipPull.Unreachable] are kept distinct so a
     *  LAN→relay fallback only fires on unreachability, not on a legitimately empty PC clipboard. */
    suspend fun pull(pairing: PcPairing): ClipPull
}

/**
 * Why a clipboard sync failed for a reason that is NOT «try another transport» (#272). Collapsing
 * every failure into one «Компьютер недоступен» hid the real causes: a 60 MB screenshot bounced by
 * the relay's blob cap, a rotated app secret, a TLS-pinning miss — each with the PC online and the
 * owner none the wiser. The invariant is «не глотай ошибки»: distinguishable failures stay distinct.
 */
enum class ClipFail {
    /** The payload exceeds the relay's blob cap — retrying transports won't shrink it. */
    TOO_BIG,

    /** The relay rejected the app secret — this build's key is stale/rotated. */
    AUTH,

    /** The pinned TLS handshake failed — the channel can't be trusted, don't keep talking. */
    TAMPERED,
}

/** The outcome of a clipboard [PcClipboardSync.push]. */
sealed interface ClipPush {
    /** Delivered to (or deposited for) the PC. */
    data object Sent : ClipPush

    /** This transport couldn't reach its far end — a caller may try the relay next. */
    data object Unreachable : ClipPush

    /** A terminal failure ([ClipFail]) — falling back to another transport cannot help. */
    data class Failed(val why: ClipFail) : ClipPush
}

/** The outcome of a clipboard [PcClipboardSync.pull]. */
sealed interface ClipPull {
    /** The PC's clipboard, read successfully. */
    data class Got(val payload: ClipboardPayload) : ClipPull

    /** The PC was reached, but its clipboard is empty — do NOT fall back to another transport. */
    data object Empty : ClipPull

    /** The PC couldn't be reached on this transport — a caller may try the relay next. */
    data object Unreachable : ClipPull

    /** A terminal failure ([ClipFail]) — falling back to another transport cannot help. */
    data class Failed(val why: ClipFail) : ClipPull
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
    private const val REQ = "req"

    internal fun metaKeys() = listOf(KIND, MIME, NAME, REQ)
}

/** A decoded clipboard relay message: its [kind], the [payload] (null for a pull request or an
 *  empty reply — i.e. whenever no content crossed), and the pull-correlation [reqId] (see
 *  [encodeClipFrame]; null on push frames and on frames from builds that predate it). */
data class ClipFrame(val kind: String, val payload: ClipboardPayload?, val reqId: String? = null)

/**
 * Seal-ready bytes for one clip message. A null [payload] means «no content» (a pull request, or an
 * empty-clipboard reply): no mime meta is written, so [decodeClipFrame] yields a null payload.
 *
 * [reqId] correlates a PULL with its REPLY: the phone stamps a fresh id on the request, the desktop
 * echoes it on the answer, and the phone accepts only the echo. Without it, a stale reply left over
 * from a timed-out pull was indistinguishable from the fresh one — the bounded drain (8 blobs) could
 * miss it, and the phone would silently set yesterday's PC clipboard as today's (#272, minor).
 */
fun encodeClipFrame(kind: String, payload: ClipboardPayload?, reqId: String? = null): ByteArray {
    val (kKey, mKey, nKey, rKey) = ClipRelay.metaKeys()
    val meta = buildMap {
        put(kKey, kind)
        payload?.let { put(mKey, it.mime); put(nKey, it.name) }
        reqId?.let { put(rKey, it) }
    }
    return encodePcFrame(meta, payload?.bytes ?: ByteArray(0))
}

fun decodeClipFrame(blob: ByteArray): ClipFrame {
    val (kKey, mKey, nKey, rKey) = ClipRelay.metaKeys()
    val frame = decodePcFrame(blob)
    val mime = frame.meta[mKey]
    val payload = if (mime == null) null else ClipboardPayload(mime, frame.meta[nKey] ?: "", frame.bytes)
    return ClipFrame(frame.meta[kKey] ?: "", payload, frame.meta[rKey])
}
