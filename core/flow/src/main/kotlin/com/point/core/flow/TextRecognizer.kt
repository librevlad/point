package com.point.core.flow

import com.point.core.model.PointObject

/**
 * On-device OCR — recognises text in an image with no network, key, or quota.
 * The realizer tries this first (free, offline, always available) and only falls
 * back to the cloud LLM for what on-device can't do (structured tables, hard
 * scans). Returns blank when nothing was recognised or the engine failed.
 */
interface TextRecognizer {
    suspend fun recognize(obj: PointObject): String
}
