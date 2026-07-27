package com.point.core.flow

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
) {
    /** The payload shown in the desktop QR / typed manually: `point-pc://host:port/token`. */
    fun qrPayload(): String = "$SCHEME$host:$port/$token"
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
    val token = rest.substring(slash + 1)
    val colon = hostPort.lastIndexOf(':')
    if (colon <= 0) return null
    val host = hostPort.substring(0, colon)
    val port = hostPort.substring(colon + 1).toIntOrNull() ?: return null
    if (host.isBlank() || token.isBlank() || port !in 1..65535) return null
    return PcPairing(host, port, token)
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

/** One action the paired PC can run on a received object (#80). */
data class PcRemoteAction(val id: String, val label: String)

/** `id=label` per line — the same dumb-simple line codec as [encodePcMeta]. */
fun encodePcCaps(caps: List<PcRemoteAction>): String =
    caps.joinToString("\n") { "${it.id}=${it.label.replace('\n', ' ').replace('\r', ' ')}" }

fun decodePcCaps(encoded: String): List<PcRemoteAction> =
    encoded.lineSequence().mapNotNull { line ->
        val eq = line.indexOf('=')
        if (eq <= 0) return@mapNotNull null
        val id = line.substring(0, eq).trim()
        val label = line.substring(eq + 1).trim()
        if (id.isEmpty() || label.isEmpty()) null else PcRemoteAction(id, label)
    }.toList()
