package com.point.executors

import com.point.core.flow.ACTION_SCHEMAS
import com.point.core.flow.AiFact
import com.point.core.flow.AiFacts
import com.point.core.flow.AiOutcome
import com.point.core.flow.CorpusCase
import com.point.core.flow.FallbackLlmClient
import com.point.core.flow.FrameForModel
import com.point.core.flow.InlineFrame
import com.point.core.flow.InvestigationState
import com.point.core.flow.NetworkAvailability
import com.point.core.flow.ObjectStore
import com.point.core.flow.OpenAiCompatibleClient
import com.point.core.flow.OutOfCount
import com.point.core.flow.Readiness
import com.point.core.flow.UrlConnectionHttpJson
import com.point.core.flow.YoloMode
import com.point.core.flow.investigationStateOf
import com.point.core.flow.openAiModels
import com.point.core.flow.readiness
import com.point.core.flow.scoreCorpus
import com.point.core.model.ActionResult
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ResultObject
import com.point.core.model.ScratchRef
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Мерный зрячий прогон корпуса (#1176, решение владельца дословно: «Я хочу такой алгоритм,
 * при котором 100% важных вещей корпуса распознаются и без ocr»).
 *
 * Каждый кадр «в счёте» идёт зрячим путём «Понять» — без единой буквы OCR: спираль крутится
 * до «нашли» или трёх витков, цепочка моделей — та же, что в приложении (ключи и модели
 * читаются из local.properties и data/build.gradle.kts, не дублируются), счёт — тот же
 * scoreCorpus по критическим полям ActionSchema.
 *
 * Это проба-инструмент с настоящей сетью, в счёте CI не участвует: без POINT_EYES_PROBE
 * тест пропускается. Запуск:
 *
 *   POINT_EYES_PROBE=1 ./gradlew :executors:testDebugUnitTest --tests "*EyesOnlyCorpusProbe*"
 */
class EyesOnlyCorpusProbe {

    @Test
    fun `зрячая спираль на корпусе — счёт критических полей`() {
        assumeTrue(System.getenv("POINT_EYES_PROBE") != null)
        val report = StringBuilder()
        fun say(line: String) {
            println(line)
            report.append(line).append(System.lineSeparator())
            System.getenv("POINT_EYES_REPORT")?.let { runCatching { File(it).writeText(report.toString()) } }
        }
        runBlocking {
            val root = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
                .first { File(it, "local.properties").isFile }
            val corpus = File(System.getenv("POINT_CORPUS") ?: "C:/Users/User/point-corpus")

            val chain = FallbackLlmClient(
                visionClients(root),
                facts = object : AiFacts {
                    override fun all(): Map<String, AiFact> = emptyMap()
                    override fun remember(providerId: String, outcome: AiOutcome) = Unit
                },
                network = NetworkAvailability { true },
                yolo = object : YoloMode {
                    override fun enabled() = true
                    override suspend fun setEnabled(enabled: Boolean) = Unit
                },
            )
            val realizer = UnderstandRealizer(chain)

            val cases = mutableListOf<CorpusCase>()
            File(root, "tools/corpus/frames.tsv").readLines()
                .filterNot { it.isBlank() || it.trimStart().startsWith("#") }
                .map { it.split("\t") }
                .forEach { row ->
                    val frame = row[0].trim()
                    val action = row[1].trim()
                    val reason = row.getOrNull(2)?.trim()?.takeIf(String::isNotBlank)
                    if (reason != null) {
                        cases += CorpusCase(frame, action, emptyMap(), OutOfCount.byWord(reason))
                        return@forEach
                    }
                    val only = System.getenv("POINT_EYES_FRAMES")?.split(',')?.map(String::trim)
                    if (only != null && frame !in only) return@forEach

                    var obj = PointObject(
                        frame, "image/jpeg",
                        ScratchRef(File(corpus, "$frame.jpg").absolutePath),
                        ObjectState(ObjectKind.IMAGE),
                    )
                    // Спираль крутится, пока виток приносит прирост: «нашли» после
                    // первого взгляда — не конец, прицельный бриф следующего витка
                    // спрашивает ненайденные категории (кадры 02/03 теряли карту,
                    // остановившись на первом же «found»).
                    var rounds = 0
                    var lastAnswer = ""
                    while (rounds < MAX_ROUNDS) {
                        rounds++
                        val outcome = runCatching { realizer.perform(obj, null) }.getOrNull()
                        val found = (outcome as? ActionResult.Done)?.findings
                        if (found == null) {
                            val why = (outcome as? ActionResult.Failure)?.reason ?: "ошибка"
                            say("$frame · виток $rounds не ответил: $why")
                            continue
                        }
                        lastAnswer = (outcome as ActionResult.Done).message
                        val before = obj.metadata
                        obj = obj.copy(
                            metadata = obj.metadata + found.metadata,
                            state = obj.state.copy(features = obj.state.features + found.features),
                        )
                        if (rounds > 1 && com.point.core.flow.spiralDelta(before, obj.metadata) == null) break
                    }
                    say(
                        "$frame · витков $rounds · " +
                            investigationStateOf(obj.metadata, UnderstandCapability.ID).wire +
                            " · смотрели: " + obj.metadata["semantic.summary.by"].orEmpty() +
                            " · итог: " + lastAnswer,
                    )
                    cases += CorpusCase(frame, action, obj.metadata)
                }

            val score = scoreCorpus(cases)
            say("")
            say("Зрячий прогон без OCR: справился сам с ${score.ready.size} из ${score.scored}")
            say("Готовы: " + score.ready.joinToString(", "))
            score.notReady.forEach { frame ->
                val case = cases.first { it.frame == frame }
                val schema = ACTION_SCHEMAS.first { it.id == case.expectedAction }
                val missing = (schema.readiness(case.facts) as? Readiness.Missing)
                    ?.missing?.joinToString(", ") { it.label }.orEmpty()
                say("$frame · не хватает: $missing")
            }
        }
    }

    /** Та же цепочка, что в приложении: ключи из local.properties, модели из data/build.gradle.kts. */
    private fun visionClients(root: File): List<OpenAiCompatibleClient> {
        val props = java.util.Properties().apply { File(root, "local.properties").inputStream().use(::load) }
        val gradle = File(root, "data/build.gradle.kts").readText()
        fun conf(field: String): String =
            props.getProperty(field)
                ?: Regex("buildConfigField\\(\"String\", \"$field\", prop\\(\"$field\", \"([^\"]*)\"\\)\\)")
                    .find(gradle)?.groupValues?.get(1).orEmpty()

        val store = object : ObjectStore {
            override suspend fun ingest(sourceUri: String, mime: String) = error("не нужен")
            override suspend fun ingestMultiple(sources: List<String>) = error("не нужен")
            override suspend fun put(
                result: ResultObject,
                from: PointObject?,
                by: com.point.core.model.CapabilityId?,
            ) = error("не нужен")
            override suspend fun children(collection: PointObject, limit: Int) = error("не нужен")
            override suspend fun readText(obj: PointObject, limit: Int) = error("не нужен")
            override suspend fun newScratchFile(extension: String) =
                ScratchRef(File.createTempFile("probe-", ".$extension").apply { deleteOnExit() }.absolutePath)
            override suspend fun clear() = Unit
        }
        val http = UrlConnectionHttpJson()
        return PROVIDERS.flatMap { id ->
            val key = props.getProperty(id + "_API_KEY").orEmpty().trim()
            if (key.isBlank()) {
                emptyList()
            } else {
                openAiModels(id.lowercase(), conf(id + "_BASE_URL"), key, conf(id + "_MODELS"))
                    .map { OpenAiCompatibleClient(http, store, it, jvmFrames) }
            }
        }
    }

    /** Кадр уходит модели как есть: корпусные снимки невелики, а зрячей пробе дорога каждая буква. */
    private val jvmFrames = FrameForModel { path, mime ->
        val bytes = runCatching { File(path).readBytes() }.getOrNull()
            ?.takeIf { it.isNotEmpty() && it.size <= MAX_FRAME_BYTES }
            ?: return@FrameForModel null
        InlineFrame(java.util.Base64.getEncoder().encodeToString(bytes), mime)
    }

    private companion object {
        const val MAX_ROUNDS = 4
        const val MAX_FRAME_BYTES = 8_000_000
        val PROVIDERS = listOf(
            "OPENROUTER", "SAMBANOVA", "MISTRAL", "CEREBRAS", "GROQ",
            "ZHIPU", "OPENAI", "MODELSCOPE", "NVIDIA",
        )
    }
}
