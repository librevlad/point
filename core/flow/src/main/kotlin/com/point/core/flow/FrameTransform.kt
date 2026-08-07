package com.point.core.flow

data class FrameTransform(
    val sample: Int,
    val rotationDegrees: Int = 0,
    val uprightWidth: Int = 0,
    val uprightHeight: Int = 0,
    val upscale: Int = 1,
) {

    init {
        require(sample >= 1) { "sample must be >= 1, was $sample" }
        require(upscale >= 1) { "upscale must be >= 1, was $upscale" }
        require(rotationDegrees in ROTATIONS) { "rotation must be one of $ROTATIONS, was $rotationDegrees" }
    }

    fun toRaw(box: Box): Box = unrotate(box).scaled()

    fun toUpright(box: Box): Box = rotate(box.unscaled())

    private fun unrotate(box: Box): Box {
        val w = uprightWidth.toFloat()
        val h = uprightHeight.toFloat()
        return when (rotationDegrees) {
            90 -> Box(box.top, w - box.right, box.bottom, w - box.left)
            180 -> Box(w - box.right, h - box.bottom, w - box.left, h - box.top)
            270 -> Box(h - box.bottom, box.left, h - box.top, box.right)
            else -> box
        }
    }

    private fun rotate(box: Box): Box {
        val w = uprightWidth.toFloat()
        val h = uprightHeight.toFloat()
        return when (rotationDegrees) {
            90 -> Box(w - box.bottom, box.left, w - box.top, box.right)
            180 -> Box(w - box.right, h - box.bottom, w - box.left, h - box.top)
            270 -> Box(box.top, h - box.right, box.bottom, h - box.left)
            else -> box
        }
    }

    private val k: Float get() = sample.toFloat() / upscale

    private fun Box.scaled(): Box = Box(left * k, top * k, right * k, bottom * k)

    private fun Box.unscaled(): Box = Box(left / k, top / k, right / k, bottom / k)

    private companion object {
        val ROTATIONS = setOf(0, 90, 180, 270)
    }
}
