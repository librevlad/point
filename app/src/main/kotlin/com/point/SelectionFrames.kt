package com.point

import android.graphics.Bitmap
import com.point.data.SelectionFrame
import com.point.data.cropRegion
import com.point.data.decodeSelectionFrame
import javax.inject.Inject

/**
 * Картинка под выделением — за контрактом (инвариант «каждый side-effect за интерфейсом»).
 *
 * Прямой вызов декодера из ViewModel делал выделение непроверяемым на JVM: любой тест утыкался в
 * `android.graphics`. Через контракт тест подставляет фейк и судит РЕШЕНИЯ выделения — открылось
 * ли оно, назвало ли отказ, — а не умение Android декодировать JPEG.
 */
interface SelectionFrames {
    /** Кадр страницы под выделение; `null` — картинку прочитать не удалось. */
    fun frame(path: String, maxPx: Int): SelectionFrame?

    /** Вырезать обведённое; `null` — вырезать не удалось. */
    fun crop(path: String, left: Int, top: Int, right: Int, bottom: Int): Bitmap?
}

class AndroidSelectionFrames @Inject constructor() : SelectionFrames {
    override fun frame(path: String, maxPx: Int) = decodeSelectionFrame(path, maxPx)

    override fun crop(path: String, left: Int, top: Int, right: Int, bottom: Int) =
        cropRegion(path, left, top, right, bottom)
}
