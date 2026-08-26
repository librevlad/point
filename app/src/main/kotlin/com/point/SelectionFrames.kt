package com.point

import com.point.data.SelectionFrame
import com.point.data.decodeSelectionFrame
import javax.inject.Inject

interface SelectionFrames {

    fun frame(path: String, maxPx: Int): SelectionFrame?
}

class AndroidSelectionFrames @Inject constructor() : SelectionFrames {
    override fun frame(path: String, maxPx: Int) = decodeSelectionFrame(path, maxPx)
}
