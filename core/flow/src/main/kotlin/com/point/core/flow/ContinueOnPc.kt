package com.point.core.flow

import java.nio.ByteBuffer

/**
 * Continue on PC (#147) — the protocol both sides share. Pure Kotlin: the phone builds
 * requests from it, the desktop parses them, and the pairing QR payload round-trips
 * through [qrPayload]/[parsePcPairing]. First embodiment of the "Liquid Software"
 * phase: the object's state (bytes + understanding metadata) crosses devices.
 */
data class PcPairing(
    val host: String,
    val port: Int,
    val token: String,
    /** The always-works relay base URL (#161 v2), when the pairing offers the firewall-proof
     *  fallback. The same [token] both authenticates the LAN hop and derives the relay E2E key. */
    val relay: String? = null,
) {
    /** `point-pc://host:port/token` — plus `?r=<base64url(relay)>` when a relay is offered. */
    fun qrPayload(): String {
        val base = "$SCHEME$host:$port/$token"
        return if (relay.isNullOrBlank()) {
            base
        } else {
            base + "?r=" + java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(relay.toByteArray(Charsets.UTF_8))
        }
    }
}

const val PC_SCHEME = "point-pc://"
private const val SCHEME = PC_SCHEME

/** Parse a pairing payload; null for anything that is not a well-formed `point-pc://` URI. */
fun parsePcPairing(payload: String): PcPairing? {
    if (!payload.startsWith(SCHEME)) return null
    val rest = payload.removePrefix(SCHEME)
    val slash = rest.indexOf('/')
    if (slash <= 0 || slash == rest.length - 1) return null
    val hostPort = rest.substring(0, slash)
    var token = rest.substring(slash + 1)
    var relay: String? = null
    val q = token.indexOf('?')
    if (q >= 0) {
        val query = token.substring(q + 1)
        token = token.substring(0, q)
        query.split('&').firstOrNull { it.startsWith("r=") }?.removePrefix("r=")
            ?.takeIf { it.isNotBlank() }
            ?.let { relay = runCatching { String(java.util.Base64.getUrlDecoder().decode(it), Charsets.UTF_8) }.getOrNull() }
    }
    val colon = hostPort.lastIndexOf(':')
    if (colon <= 0) return null
    val host = hostPort.substring(0, colon)
    val port = hostPort.substring(colon + 1).toIntOrNull() ?: return null
    if (host.isBlank() || token.isBlank() || port !in 1..65535) return null
    return PcPairing(host, port, token, relay)
}

/**
 * The object as it crosses the relay: [meta] (name/mime/understanding) + raw [bytes], framed so the
 * far side reconstructs it. The whole frame is sealed by RelayCrypto (#161 v2) — the relay server
 * only ever holds the ciphertext.
 */
class PcFrame(val meta: Map<String, String>, val bytes: ByteArray)

/** `[4-byte header length][encodePcMeta header][raw bytes]` — binary-safe, so any object survives. */
fun encodePcFrame(meta: Map<String, String>, bytes: ByteArray): ByteArray {
    val header = encodePcMeta(meta).toByteArray(Charsets.UTF_8)
    return ByteBuffer.allocate(4 + header.size + bytes.size)
        .putInt(header.size).put(header).put(bytes).array()
}

fun decodePcFrame(blob: ByteArray): PcFrame {
    val buffer = ByteBuffer.wrap(blob)
    val headerLen = buffer.int
    require(headerLen in 0..buffer.remaining()) { "malformed frame" }
    val header = ByteArray(headerLen).also(buffer::get)
    val bytes = ByteArray(buffer.remaining()).also(buffer::get)
    return PcFrame(decodePcMeta(String(header, Charsets.UTF_8)), bytes)
}

/**
 * Understanding travels with the object: metadata as `key=value` lines. Values lose
 * line breaks (collapsed to spaces) — the format stays trivially parseable on any side.
 */
fun encodePcMeta(meta: Map<String, String>): String =
    meta.entries.joinToString("\n") { (k, v) ->
        "${k.replace('\n', ' ')}=${v.replace('\n', ' ')}"
    }

fun decodePcMeta(encoded: String): Map<String, String> =
    encoded.lineSequence()
        .filter { it.contains('=') }
        .associate { line ->
            val i = line.indexOf('=')
            line.substring(0, i) to line.substring(i + 1)
        }

/** One action the paired PC can run on a received object (#80).
 *  [kinds] — ObjectKind names the action makes sense for; empty = any kind. */
data class PcRemoteAction(val id: String, val label: String, val kinds: Set<String> = emptySet())

/** `id=label` per line, optionally `id=label<TAB>KIND1,KIND2` — the same dumb-simple
 *  line codec as [encodePcMeta]; a tab never appears in a human label. */
fun encodePcCaps(caps: List<PcRemoteAction>): String =
    caps.joinToString("\n") { action ->
        val label = action.label.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ')
        val gate = if (action.kinds.isEmpty()) "" else "\t" + action.kinds.joinToString(",")
        "${action.id}=$label$gate"
    }

fun decodePcCaps(encoded: String): List<PcRemoteAction> =
    encoded.lineSequence().mapNotNull { line ->
        val eq = line.indexOf('=')
        if (eq <= 0) return@mapNotNull null
        val id = line.substring(0, eq).trim()
        val rest = line.substring(eq + 1)
        val label = rest.substringBefore('\t').trim()
        val kinds = rest.substringAfter('\t', "").split(',')
            .mapNotNull { it.trim().takeIf(String::isNotEmpty) }.toSet()
        if (id.isEmpty() || label.isEmpty()) null else PcRemoteAction(id, label, kinds)
    }.toList()

/** One object waiting in the PC's outbox for the phone to pull (#161).
 *  The display name and mime live inside [meta] («name», «mime»). */
data class PcOutboxEntry(val id: Int, val meta: Map<String, String>)

/** `id<TAB>base64(encodePcMeta(meta))` per line — base64 keeps tabs and newlines
 *  inside metadata values from ever breaking the line format. */
fun encodePcOutbox(entries: List<PcOutboxEntry>): String =
    entries.joinToString("\n") { entry ->
        val meta = java.util.Base64.getEncoder().encodeToString(encodePcMeta(entry.meta).toByteArray())
        "${entry.id}\t$meta"
    }

fun decodePcOutbox(encoded: String): List<PcOutboxEntry> =
    encoded.lineSequence().mapNotNull { line ->
        val tab = line.indexOf('\t')
        if (tab <= 0) return@mapNotNull null
        val id = line.substring(0, tab).trim().toIntOrNull() ?: return@mapNotNull null
        val meta = runCatching {
            decodePcMeta(String(java.util.Base64.getDecoder().decode(line.substring(tab + 1).trim())))
        }.getOrNull() ?: return@mapNotNull null
        PcOutboxEntry(id, meta)
    }.toList()
