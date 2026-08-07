package com.point.core.flow

import kotlin.math.abs
import kotlin.math.max

class QrMatrix internal constructor(
    val size: Int,
    private val modules: BooleanArray,
) {

    operator fun get(x: Int, y: Int): Boolean =
        x in 0 until size && y in 0 until size && modules[y * size + x]
}

const val QR_MAX_BYTES = 106

fun qrMatrix(text: String): QrMatrix? {
    val data = text.toByteArray(Charsets.UTF_8)
    if (data.isEmpty() || data.size > QR_MAX_BYTES) return null
    val version = (1..QR_MAX_VERSION).first { data.size <= dataCodewords(it) - 2 }
    val codewords = interleave(version, data)

    val size = 4 * version + 17
    val modules = BooleanArray(size * size)
    val function = BooleanArray(size * size)
    drawFunctionPatterns(version, size, modules, function)
    drawCodewords(size, modules, function, codewords)

    var best = 0
    var bestPenalty = Int.MAX_VALUE
    for (mask in 0..7) {
        applyMask(size, modules, function, mask)
        drawFormatBits(size, modules, function, mask)
        val penalty = penalty(size, modules)
        if (penalty < bestPenalty) {
            bestPenalty = penalty
            best = mask
        }
        applyMask(size, modules, function, mask)
    }
    applyMask(size, modules, function, best)
    drawFormatBits(size, modules, function, best)
    return QrMatrix(size, modules)
}

private const val QR_MAX_VERSION = 6

private fun dataCodewords(version: Int): Int =
    intArrayOf(16, 28, 44, 64, 86, 108)[version - 1]

private fun ecCodewords(version: Int): Int =
    intArrayOf(10, 16, 26, 18, 24, 16)[version - 1]

private fun blocks(version: Int): Int =
    intArrayOf(1, 1, 1, 2, 2, 4)[version - 1]

private fun interleave(version: Int, data: ByteArray): IntArray {
    val total = dataCodewords(version)
    val bits = BitBuffer()
    bits.append(0b0100, 4)
    bits.append(data.size, 8)
    data.forEach { bits.append(it.toInt() and 0xFF, 8) }
    bits.append(0, minOf(4, total * 8 - bits.size))
    bits.append(0, (8 - bits.size % 8) % 8)
    var pad = 0xEC
    while (bits.size < total * 8) {
        bits.append(pad, 8)
        pad = pad xor (0xEC xor 0x11)
    }

    val perBlock = total / blocks(version)
    val ecLen = ecCodewords(version)
    val dataBlocks = Array(blocks(version)) { b -> IntArray(perBlock) { i -> bits.byteAt(b * perBlock + i) } }
    val divisor = rsDivisor(ecLen)
    val ecBlocks = Array(blocks(version)) { b -> rsRemainder(dataBlocks[b], divisor) }

    val out = IntArray(total + ecLen * blocks(version))
    var k = 0
    for (i in 0 until perBlock) for (block in dataBlocks) out[k++] = block[i]
    for (i in 0 until ecLen) for (block in ecBlocks) out[k++] = block[i]
    return out
}

private class BitBuffer {
    private val bits = ArrayList<Boolean>()
    val size: Int get() = bits.size

    fun append(value: Int, count: Int) {
        for (i in count - 1 downTo 0) bits.add((value ushr i) and 1 != 0)
    }

    fun byteAt(index: Int): Int {
        var v = 0
        for (i in 0 until 8) v = (v shl 1) or (if (bits[index * 8 + i]) 1 else 0)
        return v
    }
}

private fun gfMul(x: Int, y: Int): Int {
    var z = 0
    for (i in 7 downTo 0) {
        z = (z shl 1) xor ((z ushr 7) * 0x11D)
        z = z xor (((y ushr i) and 1) * x)
    }
    return z and 0xFF
}

private fun rsDivisor(degree: Int): IntArray {
    val result = IntArray(degree)
    result[degree - 1] = 1
    var root = 1
    repeat(degree) {
        for (i in 0 until degree) {
            result[i] = gfMul(result[i], root)
            if (i + 1 < degree) result[i] = result[i] xor result[i + 1]
        }
        root = gfMul(root, 0x02)
    }
    return result
}

private fun rsRemainder(data: IntArray, divisor: IntArray): IntArray {
    val result = IntArray(divisor.size)
    for (b in data) {
        val factor = b xor result[0]
        for (i in 0 until result.size - 1) result[i] = result[i + 1]
        result[result.size - 1] = 0
        for (i in result.indices) result[i] = result[i] xor gfMul(divisor[i], factor)
    }
    return result
}

private fun drawFunctionPatterns(
    version: Int,
    size: Int,
    modules: BooleanArray,
    function: BooleanArray,
) {
    fun set(x: Int, y: Int, dark: Boolean) {
        if (x in 0 until size && y in 0 until size) {
            modules[y * size + x] = dark
            function[y * size + x] = true
        }
    }

    for (i in 0 until size) {
        set(6, i, i % 2 == 0)
        set(i, 6, i % 2 == 0)
    }

    for ((cx, cy) in listOf(3 to 3, size - 4 to 3, 3 to size - 4)) {
        for (dy in -4..4) for (dx in -4..4) {
            val dist = max(abs(dx), abs(dy))
            set(cx + dx, cy + dy, dist != 2 && dist != 4)
        }
    }

    if (version >= 2) {
        val c = size - 7
        for (dy in -2..2) for (dx in -2..2) set(c + dx, c + dy, max(abs(dx), abs(dy)) != 1)
    }

    drawFormatBits(size, modules, function, 0)
}

private fun drawFormatBits(size: Int, modules: BooleanArray, function: BooleanArray, mask: Int) {
    val data = mask
    var rem = data
    repeat(10) { rem = (rem shl 1) xor ((rem ushr 9) * 0x537) }
    val bits = ((data shl 10) or rem) xor 0x5412

    fun set(x: Int, y: Int, dark: Boolean) {
        modules[y * size + x] = dark
        function[y * size + x] = true
    }
    fun bit(i: Int) = (bits ushr i) and 1 != 0

    for (i in 0..5) set(8, i, bit(i))
    set(8, 7, bit(6))
    set(8, 8, bit(7))
    set(7, 8, bit(8))
    for (i in 9..14) set(14 - i, 8, bit(i))

    for (i in 0..7) set(size - 1 - i, 8, bit(i))
    for (i in 8..14) set(8, size - 15 + i, bit(i))
    set(8, size - 8, true)
}

private fun drawCodewords(size: Int, modules: BooleanArray, function: BooleanArray, codewords: IntArray) {
    var i = 0
    var right = size - 1
    while (right >= 1) {
        if (right == 6) right = 5
        for (vert in 0 until size) {
            for (j in 0..1) {
                val x = right - j
                val upward = ((right + 1) and 2) == 0
                val y = if (upward) size - 1 - vert else vert
                if (!function[y * size + x] && i < codewords.size * 8) {
                    modules[y * size + x] = (codewords[i ushr 3] ushr (7 - (i and 7))) and 1 != 0
                    i++
                }
            }
        }
        right -= 2
    }
}

private fun applyMask(size: Int, modules: BooleanArray, function: BooleanArray, mask: Int) {
    for (y in 0 until size) for (x in 0 until size) {
        if (function[y * size + x]) continue
        val invert = when (mask) {
            0 -> (x + y) % 2 == 0
            1 -> y % 2 == 0
            2 -> x % 3 == 0
            3 -> (x + y) % 3 == 0
            4 -> (y / 2 + x / 3) % 2 == 0
            5 -> (x * y) % 2 + (x * y) % 3 == 0
            6 -> ((x * y) % 2 + (x * y) % 3) % 2 == 0
            else -> ((x + y) % 2 + (x * y) % 3) % 2 == 0
        }
        if (invert) modules[y * size + x] = !modules[y * size + x]
    }
}

private fun penalty(size: Int, modules: BooleanArray): Int {
    fun dark(x: Int, y: Int) = modules[y * size + x]
    var score = 0

    for (i in 0 until size) {
        var runRow = 1
        var runCol = 1
        for (j in 1 until size) {
            runRow = if (dark(j, i) == dark(j - 1, i)) runRow + 1 else 1
            if (runRow == 5) score += 3 else if (runRow > 5) score += 1
            runCol = if (dark(i, j) == dark(i, j - 1)) runCol + 1 else 1
            if (runCol == 5) score += 3 else if (runCol > 5) score += 1
        }
    }

    for (y in 0 until size - 1) for (x in 0 until size - 1) {
        val c = dark(x, y)
        if (c == dark(x + 1, y) && c == dark(x, y + 1) && c == dark(x + 1, y + 1)) score += 3
    }

    val eye = booleanArrayOf(true, false, true, true, true, false, true)
    fun eyeAt(at: (Int) -> Boolean, start: Int): Boolean {
        for (k in eye.indices) if (at(start + k) != eye[k]) return false
        val before = (1..4).all { !at(start - it) }
        val after = (0..3).all { !at(start + eye.size + it) }
        return before || after
    }
    for (i in 0 until size) {
        val row = { j: Int -> j in 0 until size && dark(j, i) }
        val col = { j: Int -> j in 0 until size && dark(i, j) }
        for (start in 0..size - eye.size) {
            if (eyeAt(row, start)) score += 40
            if (eyeAt(col, start)) score += 40
        }
    }

    val percent = modules.count { it } * 100 / (size * size)
    score += (abs(percent - 50) / 5) * 10
    return score
}
