package com.point

import android.graphics.Bitmap
import com.point.data.SelectionFrame
import com.point.data.cropRegion
import com.point.data.decodeSelectionFrame
import javax.inject.Inject

interface SelectionFrames {

    fun frame(path: String, maxPx: Int): SelectionFrame?

    fun crop(path: String, left: Int, top: Int, right: Int, bottom: Int): Bitmap?
}

class AndroidSelectionFrames @Inject constructor() : SelectionFrames {
    override fun frame(path: String, maxPx: Int) = decodeSelectionFrame(path, maxPx)

    override fun crop(path: String, left: Int, top: Int, right: Int, bottom: Int) =
        cropRegion(path, left, top, right, bottom)
}
