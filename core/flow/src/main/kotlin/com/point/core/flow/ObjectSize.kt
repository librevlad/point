package com.point.core.flow

import com.point.core.model.tr
import kotlin.math.roundToInt

const val META_SIZE = "size"

fun humanWeight(bytes: Long): String? {
    if (bytes <= 0L) return null
    val kb = bytes / 1024.0
    if (kb < 1.0) return tr("{0} Б", bytes)
    if (kb < 1024.0) return tr("{0} КБ", kb.roundToInt())
    val mb = kb / 1024.0
    if (mb < 1024.0) return tr("{0} МБ", oneDecimal(mb))
    return tr("{0} ГБ", oneDecimal(mb / 1024.0))
}

private fun oneDecimal(value: Double): String {
    val tenths = (value * 10).roundToInt()
    val whole = tenths / 10
    val rest = tenths % 10
    return if (rest == 0) "$whole" else "$whole,$rest"
}
