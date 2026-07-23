package com.point

import java.io.File

/**
 * Materialise shared or selected text into a temp file, so it enters the flow through the same
 * `onShared(fileUri, "text/plain")` path as any other object. Shared by [ShareActivity] (ACTION_SEND
 * text) and [ProcessTextActivity] (ACTION_PROCESS_TEXT).
 */
internal fun cacheTextFile(cacheDir: File, text: String): File =
    File.createTempFile("shared-", ".txt", cacheDir).apply { writeText(text) }
