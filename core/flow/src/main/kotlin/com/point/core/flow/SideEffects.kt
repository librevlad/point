package com.point.core.flow

import com.point.core.model.PointObject

/**
 * Activity-free side-effect contracts for terminal executors. Implemented in
 * :data with the application context (no Activity needed), so ShareExecutor /
 * SaveExecutor stay independent and testable with fakes.
 */

/** Launches the system share sheet for an object. */
interface Sharer {
    suspend fun share(obj: PointObject)
}

/** Exports an object to shared storage. */
interface Exporter {
    /** @return a short, user-facing location (e.g. "Downloads/report.pdf"). */
    suspend fun export(obj: PointObject): String
}

/** Opens a URL in the system browser. */
interface UrlOpener {
    suspend fun open(url: String)
}
