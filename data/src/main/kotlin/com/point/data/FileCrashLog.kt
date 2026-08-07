package com.point.data

import com.point.core.flow.CrashLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FileCrashLog @Inject constructor(
    @com.point.data.di.CrashLogFile private val file: File,
) : CrashLog {

    override fun record(report: String) {
        runCatching { file.writeText(report) }
    }

    override suspend fun pending(): String? = withContext(Dispatchers.IO) {
        runCatching { file.takeIf { it.isFile }?.readText()?.ifBlank { null } }.getOrNull()
    }

    override suspend fun clear(): Unit = withContext(Dispatchers.IO) {
        runCatching { file.delete() }
    }
}
