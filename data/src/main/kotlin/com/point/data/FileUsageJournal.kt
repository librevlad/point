package com.point.data

import com.point.core.flow.UsageEvent
import com.point.core.flow.UsageEventType
import com.point.core.flow.UsageJournal
import com.point.core.flow.UsageSummary
import com.point.data.di.UsageDir
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Append-only JSONL journal plus a consent marker file, both under a private dir. No
 * Room, no network — a plain store the [UsageDir] is injected into, so it unit-tests
 * on the JVM. Consent is a marker file (not prefs) to keep it Android-context-free;
 * flipping it off deletes the journal too.
 *
 * A [Mutex] serialises the consent check and every file mutation, so a concurrent
 * opt-out can't leave a journal behind and concurrent records can't interleave lines.
 */
@Singleton
class FileUsageJournal @Inject constructor(
    @UsageDir private val baseDir: File,
) : UsageJournal {

    private val lock = Mutex()

    private val dir: File get() = baseDir.apply { mkdirs() }
    private val journal: File get() = File(dir, "usage.jsonl")
    private val consent: File get() = File(dir, "consent")

    override suspend fun isEnabled(): Boolean = withContext(Dispatchers.IO) { consent.exists() }

    override suspend fun setEnabled(enabled: Boolean): Unit = withContext(Dispatchers.IO) {
        lock.withLock {
            if (enabled) {
                consent.createNewFile()
            } else {
                consent.delete()
                journal.delete()
            }
        }
    }

    override suspend fun record(event: UsageEvent): Unit = withContext(Dispatchers.IO) {
        lock.withLock {
            if (!consent.exists()) return@withLock // opt-out wins the race
            val row = JSONObject().put("t", event.type.name).put("d", event.detail)
            journal.appendText(row.toString() + "\n")
        }
    }

    override suspend fun summary(): UsageSummary = withContext(Dispatchers.IO) {
        lock.withLock {
            if (!journal.exists()) return@withLock UsageSummary(0, 0, 0)
            var objects = 0
            var actions = 0
            var completed = 0
            runCatching {
                journal.forEachLine { line ->
                    if (line.isBlank()) return@forEachLine
                    runCatching {
                        when (JSONObject(line).getString("t")) {
                            UsageEventType.SHARED.name -> objects++
                            UsageEventType.ACTION.name -> actions++
                            UsageEventType.COMPLETED.name -> completed++
                        }
                    }
                }
            }
            UsageSummary(objects, actions, completed)
        }
    }

    override suspend fun clear(): Unit = withContext(Dispatchers.IO) {
        lock.withLock { journal.delete() }
    }
}
