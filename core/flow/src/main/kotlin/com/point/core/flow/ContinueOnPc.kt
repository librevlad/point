package com.point.core.flow

import java.nio.ByteBuffer

data class LinkedPc(
    val deviceId: String,
    val name: String,
    val key: String = "",
)

class PcFrame(val meta: Map<String, String>, val bytes: ByteArray)

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

data class PcRemoteAction(
    val id: String,
    val label: String,
    val kinds: Set<String> = emptySet(),
    val unavailable: String? = null,

    val leavesCircle: Boolean = false,

    val features: Set<String> = emptySet(),
)

fun encodePcCaps(caps: List<PcRemoteAction>): String =
    caps.joinToString("\n") { action ->
        val label = oneLine(action.label)
        val kinds = action.kinds.joinToString(",")
        val why = action.unavailable

        val fields = listOf(
            kinds,
            why?.let(::oneLine).orEmpty(),
            if (action.leavesCircle) "out" else "",
            action.features.sorted().joinToString(","),
        ).dropLastWhile(String::isEmpty)
        val head = if (why == null) action.id else PC_CAP_UNAVAILABLE + action.id
        if (fields.isEmpty()) "$head=$label" else "$head=$label\t" + fields.joinToString("\t")
    }

fun decodePcCaps(encoded: String): List<PcRemoteAction> =
    encoded.lineSequence().mapNotNull { raw ->
        val unavailableLine = raw.startsWith(PC_CAP_UNAVAILABLE)
        val line = if (unavailableLine) raw.substring(PC_CAP_UNAVAILABLE.length) else raw
        val eq = line.indexOf('=')
        if (eq <= 0) return@mapNotNull null
        val id = line.substring(0, eq).trim()

        val fields = line.substring(eq + 1).split('\t')
        val label = fields[0].trim()
        val kinds = fields.getOrElse(1) { "" }.split(',')
            .mapNotNull { it.trim().takeIf(String::isNotEmpty) }.toSet()
        val why = if (unavailableLine) fields.getOrElse(2) { "" }.trim() else null
        val leaves = fields.getOrElse(3) { "" }.trim() == "out"
        val needs = fields.getOrElse(4) { "" }.split(',')
            .mapNotNull { it.trim().takeIf(String::isNotEmpty) }.toSet()
        if (id.isEmpty() || label.isEmpty()) null else PcRemoteAction(id, label, kinds, why, leaves, needs)
    }.toList()

const val PC_CAP_UNAVAILABLE = "="

fun encodePcReceiveReply(outcome: PcActionOutcome?): String = when (outcome) {
    null -> PC_RECEIVE_OK
    is PcActionOutcome.Done ->
        PC_RECEIVE_OK + "\n" + PC_ACTION_LINE + "done" + (outcome.detail?.let { " " + oneLine(it) } ?: "")
    is PcActionOutcome.Failed ->
        PC_RECEIVE_OK + "\n" + PC_ACTION_LINE + "failed " + oneLine(outcome.reason).ifBlank { "причина не названа" }
}

fun decodePcReceiveReply(body: String): PcActionOutcome? {
    val line = body.lineSequence()
        .map { it.trim() }
        .firstOrNull { it.startsWith(PC_ACTION_LINE) }
        ?.removePrefix(PC_ACTION_LINE)
        ?.trim()
        ?: return null
    val verdict = line.substringBefore(' ')
    val detail = line.substringAfter(' ', "").trim()
    return when (verdict) {
        "done" -> PcActionOutcome.Done(detail.takeIf { it.isNotBlank() })
        "failed" -> PcActionOutcome.Failed(detail.ifBlank { "причина не названа" })
        else -> null
    }
}

fun pcActionOutcomeOf(result: com.point.core.model.ActionResult?): PcActionOutcome? = when (result) {
    null -> null
    is com.point.core.model.ActionResult.Done -> PcActionOutcome.Done(result.message)
    is com.point.core.model.ActionResult.Success -> PcActionOutcome.Done(null)
    is com.point.core.model.ActionResult.Failure -> PcActionOutcome.Failed(result.reason)
    is com.point.core.model.ActionResult.NeedsInput, is com.point.core.model.ActionResult.NeedsImage ->
        PcActionOutcome.Failed("действие спрашивает на компьютере — ответить отсюда нечем")
}

private const val PC_RECEIVE_OK = "ok"

const val PC_ACTION_LINE = "action: "

private fun oneLine(s: String) = s.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ')

data class PcOutboxEntry(val id: Int, val meta: Map<String, String>)

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

class PcReturned(
    val name: String,
    val mime: String,
    val bytes: ByteArray,
    val understanding: Map<String, String> = emptyMap(),
)

object PcResultFields {
    const val NAME = "result.name"
    const val MIME = "result.mime"
    const val OUTCOME = "result.outcome"
    const val DETAIL = "result.detail"

    const val UNDERSTOOD = "result.understood."

    const val DONE = "done"
    const val FAILED = "failed"

    fun hasObject(meta: Map<String, String>): Boolean = !meta[NAME].isNullOrBlank()
}
