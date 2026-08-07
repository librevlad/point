package com.point.data

import com.point.core.flow.AtomRecognizer
import com.point.core.flow.Box
import com.point.core.model.PointObject

interface CloudAtomRecognizer : AtomRecognizer {

    val reader: String

    val configured: Boolean

    fun canRead(obj: PointObject): Boolean = obj.mime.startsWith("image/")
}

internal fun OutboundFrame.toRawFrame(box: Box, layoutWidth: Float, layoutHeight: Float): Box {
    val sentWidth = transform.uprightWidth.toFloat()
    val sentHeight = transform.uprightHeight.toFloat()
    val declared = layoutWidth > 0f && layoutHeight > 0f && sentWidth > 0f && sentHeight > 0f
    val scaleX = if (declared) sentWidth / layoutWidth else 1f
    val scaleY = if (declared) sentHeight / layoutHeight else 1f
    val onSentCopy = Box(box.left * scaleX, box.top * scaleY, box.right * scaleX, box.bottom * scaleY)
    return transform.toRaw(onSentCopy)
}
