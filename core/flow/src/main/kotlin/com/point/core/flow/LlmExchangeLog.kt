package com.point.core.flow

import com.point.core.model.PointObject
import com.point.core.model.ResultObject
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * Журнал обменов с моделью (просьба владельца 2026-08-09): каждый запрос и сырой
 * ответ ложатся файлом в [dir], живут последние [KEEP], старые удаляются. Ошибка
 * провайдера — тоже запись. Содержимое — личные данные человека, поэтому журнал
 * включается только на отладочных стендах и никогда не покидает устройство.
 */
class LoggingLlmClient(
    private val inner: LlmClient,
    private val dir: File,
    private val enabled: Boolean,
    private val now: () -> Long = System::currentTimeMillis,
) : LlmClient {

    private val seq = AtomicLong(0)

    override val configured: Boolean get() = inner.configured

    override val strongVision: Boolean get() = inner.strongVision

    override fun canHandle(obj: PointObject): Boolean = inner.canHandle(obj)

    override suspend fun run(obj: PointObject, prompt: String): ResultObject {
        val result = runCatching { inner.run(obj, prompt) }
        if (enabled) runCatching { record(obj, prompt, result) }
        return result.getOrThrow()
    }

    private fun record(obj: PointObject, prompt: String, result: Result<ResultObject>) {
        dir.mkdirs()
        val answer = result.fold(
            onSuccess = { res ->
                runCatching { File(res.uri.value).takeIf(File::isFile)?.readText() }.getOrNull()
                    ?: "<ответ не текстом>"
            },
            onFailure = { "<ошибка: ${it.message}>" },
        )
        File(dir, "llm-%013d-%03d.txt".format(now(), seq.incrementAndGet() % 1000)).writeText(
            "=== OBJECT ===\n${obj.id}\n=== PROMPT ===\n$prompt\n=== ANSWER ===\n$answer\n",
        )
        dir.listFiles { f -> f.name.startsWith("llm-") }
            ?.sortedByDescending(File::getName)
            ?.drop(KEEP)
            ?.forEach(File::delete)
    }

    private companion object { const val KEEP = 30 }
}
