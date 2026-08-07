package com.point

import com.point.core.flow.SharedTexts
import java.io.File

class FakeSharedTexts(
    private val dir: File = File(System.getProperty("java.io.tmpdir"), "point-shared-" + System.nanoTime()),
) : SharedTexts {

    override fun create(text: String): String {
        dir.mkdirs()
        return File.createTempFile("shared-", ".txt", dir).apply { writeText(text) }.absolutePath
    }

    override fun clear() {
        dir.listFiles()?.forEach { it.delete() }
    }

    fun files(): List<File> = dir.listFiles()?.toList().orEmpty()
}
