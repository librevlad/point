package com.point.core.flow

import kotlin.math.roundToInt

const val META_SIZE = "size"

fun humanWeight(bytes: Long): String? {
    if (bytes <= 0L) return null
    val kb = bytes / 1024.0
    if (kb < 1.0) return "$bytes Б"
    if (kb < 1024.0) return "${kb.roundToInt()} КБ"
    val mb = kb / 1024.0
    if (mb < 1024.0) return "${oneDecimal(mb)} МБ"
    return "${oneDecimal(mb / 1024.0)} ГБ"
}

private fun oneDecimal(value: Double): String {
    val tenths = (value * 10).roundToInt()
    val whole = tenths / 10
    val rest = tenths % 10
    return if (rest == 0) "$whole" else "$whole,$rest"
}
