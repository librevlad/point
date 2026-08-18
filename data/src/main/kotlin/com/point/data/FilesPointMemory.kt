package com.point.data

import com.point.core.flow.FlowSnapshotStore
import com.point.core.flow.HistoryFootprint
import com.point.core.flow.HistoryStore
import com.point.core.flow.ObjectStore
import com.point.core.flow.PointMemory
import com.point.data.di.HistoryDir
import com.point.data.di.LlmLogDir
import com.point.data.di.ScratchDir
import com.point.data.di.FlowSnapshotFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/**
 * Память об объектах — четырьмя местами на диске, одним вопросом снаружи (#1026).
 *
 * Перечень «Недавнего», копии объектов рядом с копиями улик, снимок текущего разбора и
 * журнал обменов с моделью. Каждое из них уже умеет себя стирать — здесь их спрашивают
 * вместе, потому что человеку они все одно: «что Point помнит».
 */
class FilesPointMemory @Inject constructor(
    private val history: HistoryStore,
    private val snapshot: FlowSnapshotStore,
    private val objects: ObjectStore,
    @HistoryDir private val historyDir: File,
    @ScratchDir private val scratchDir: File,
    @FlowSnapshotFile private val snapshotFile: File,
    @LlmLogDir private val exchangesDir: File,
) : PointMemory {

    override suspend fun footprint(): HistoryFootprint = withContext(Dispatchers.IO) {
        HistoryFootprint(
            count = runCatching { history.footprint().count }.getOrDefault(0),
            bytes = places().sumOf(::weigh),
        )
    }

    /**
     * Сначала посчитать, потом стереть: сказать человеку, чего он лишился, можно только тем
     * числом, которое было до уборки.
     */
    override suspend fun forgetAll(): HistoryFootprint {
        val was = footprint()
        withContext(Dispatchers.IO) {
            runCatching { history.clearAll() }
            runCatching { snapshot.clear() }

            // Копия объекта в scratch — тот самый объект, который человек просил забыть, а
            // не служебный след: без неё «Забыть всё» оставляло на устройстве саму вещь.
            runCatching { objects.clear() }
            runCatching { exchangesDir.deleteRecursively() }
        }
        return was
    }

    private fun places(): List<File> = listOf(historyDir, scratchDir, snapshotFile, exchangesDir)

    private fun weigh(place: File): Long = runCatching {
        if (!place.exists()) 0L else place.walkTopDown().filter(File::isFile).sumOf(File::length)
    }.getOrDefault(0L)
}
