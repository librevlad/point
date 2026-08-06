package com.point.executors

import com.point.core.flow.Capability
import com.point.core.flow.CapabilityMeta
import com.point.core.flow.Latency
import com.point.core.flow.PcActionOutcome
import com.point.core.flow.PC_DEVICE_REVOKED
import com.point.core.flow.PcLinks
import com.point.core.flow.PcRemoteAction
import com.point.core.flow.PcSendOutcome
import com.point.core.flow.PcTransport
import com.point.core.flow.pcUnreachableText
import com.point.core.flow.Realizer
import com.point.core.flow.reportStage
import com.point.core.model.ActionResult
import com.point.core.model.CapabilityId
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.isFileBacked
import com.point.core.model.PointObject

/**
 * A PC-advertised action as a phone bubble (#80): «Открыть на компьютере» sits beside
 * the local actions, indistinguishable in the UI — the realizer ships the object over
 * the one channel there is with the action id, and the PC runs it. Synthesised one
 * pair per cached advertisement, exactly like remembered app picks (#66).
 *
 * #316: компьютер умеет объявить действие недоступным с причиной («нет принтера»). Такое
 * действие не становится кнопкой — оно уходит в «Почти доступно» (#97) той же причиной,
 * без обещаний: раньше принтера не было → действие не объявлялось вовсе → человек читал это
 * как «Point не умеет печатать», хотя умеет — печатать некуда именно сейчас.
 */
class RemotePcCapability(
    private val action: PcRemoteAction,
    private val links: PcLinks,
) : Capability {
    override val id = idFor(action)
    override val icon = "pc"
    // Между своими устройствами, запечатанно: network=false НАМЕРЕННО (та же причина, что у
    // «На компьютер»).
    // `localOnly`: это и есть действия ЧУЖОГО устройства — объявлять их обратно значит
    // отправить объект по кругу (#588).
    override val meta = CapabilityMeta(priority = 76, latency = Latency.FAST, localOnly = true)
    override fun label(state: ObjectState) = action.label
    override fun accepts(state: ObjectState) =
        action.unavailable == null && fitsThisObject(state)

    override fun produces(state: ObjectState) = state // terminal — the action happens on the PC

    /** Причина показывается только там, где кнопка и была бы: объект подходящего вида и
     *  компьютер на связи. Иначе «нет принтера» всплывёт рядом с объектом, который на ПК
     *  вообще не поедет, — шум вместо объяснения. Причины нет → и подсказки нет (молчание
     *  честнее выдуманного текста). */
    override fun missing(state: ObjectState): String? =
        action.unavailable?.takeIf { it.isNotBlank() && fitsThisObject(state) }

    private fun fitsThisObject(state: ObjectState) =
        // Объект без файла отправляется (#611): его значение и есть его содержимое. Набор — нет:
        // это не один груз, и как его везти, ещё не решено.
        state.kind != ObjectKind.COLLECTION &&
            (action.kinds.isEmpty() || state.kind.name in action.kinds) &&
            links.current() != null

    companion object {
        /**
         * Под какой способностью живёт то, что объявил компьютер.
         *
         * Намерение из общего словаря (контракт 06.08.2026, И1) остаётся **собой**: реализация
         * компьютера встаёт кандидатом к той же способности, что и местная, и выбирает между ними
         * `Resolver`. Отдельного `pc-do:ocr` не заводится — это была бы вторая декларация одного
         * намерения, то есть «Распознать текст на ПК» рядом с «Распознать текст».
         *
         * Всё, что в словарь ещё не переехало, живёт по-старому — под своим `pc-do:` — и
         * переедет вместе со своей способностью.
         */
        fun idFor(action: PcRemoteAction): CapabilityId {
            val shared = CapabilityId(action.id)
            return if (shared in com.point.core.flow.capabilities.sharedCapabilityIds) {
                shared
            } else {
                CapabilityId("pc-do:${action.id}")
            }
        }
    }
}

class RemotePcRealizer(
    private val action: PcRemoteAction,
    private val links: PcLinks,
    private val transport: PcTransport,
    /**
     * Куда положить то, что вернул компьютер. `null` — старое поведение: вернувшийся объект
     * останется на компьютере, а сюда придёт только слово.
     */
    private val store: com.point.core.flow.ObjectStore? = null,
) : Realizer {
    override val capabilityId = RemotePcCapability.idFor(action)

    /**
     * Между своими устройствами объект едет запечатанным — это `LOCAL`, и согласия не требует
     * (контракт 06.08.2026, граница молчаливого выбора). Но если компьютер сказал, что ЕГО
     * реализация увезёт объект к чужому сервису, то путь облачный — и согласие спросит телефон,
     * до отправки, там, где человек.
     */
    override val meta = com.point.core.flow.RealizerMeta(
        kind = if (action.leavesCircle) com.point.core.flow.RealizerKind.CLOUD else com.point.core.flow.RealizerKind.LOCAL,
    )

    /**
     * Компьютер вернул объект — он становится объектом ЗДЕСЬ, где человек и нажимал.
     *
     * Ради этого всё и делалось: человек, попросивший с телефона распознать снимок, до сих пор шёл
     * за текстом к компьютеру. Работа кончалась словом «готово», а результат оставался на другом
     * устройстве.
     *
     * `null` — возвращать нечего либо некуда: тогда зовущий скажет прежними словами.
     */
    private suspend fun materialize(outcome: PcSendOutcome.Sent): ActionResult? {
        val returned = outcome.returned ?: return null
        val place = store ?: return null
        return runCatching {
            val ref = place.newScratchFile(returned.name.substringAfterLast('.', "bin"))
            java.io.File(ref.value).writeBytes(returned.bytes)
            ActionResult.Success(
                com.point.core.model.ResultObject(
                    type = com.point.core.model.ObjectKind.UNKNOWN,
                    mime = returned.mime,
                    uri = ref,
                    metadata = returned.understanding + ("name" to returned.name),
                ),
            )
        }.getOrNull()
    }

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult {
        // #316: недоступное не отправляется никогда — даже если до реализатора добрались в
        // обход экрана (сохранённая цепочка, устаревший кэш действий ПК). Объект остаётся
        // на телефоне, человек читает ту же причину, что и в «Почти доступно».
        action.unavailable?.let { why ->
            val reason = "Компьютер сейчас не может это сделать" + if (why.isBlank()) "" else " — $why"
            return ActionResult.Failure(reason, recoverable = true)
        }
        val pc = links.current()
            ?: return ActionResult.Failure(
                pcUnreachableText(com.point.core.flow.PcUnreachable.NOT_IN_CIRCLE),
                recoverable = true,
            )
        val name = input.metadata["name"] ?: "объект"
        // Те же слова, что у «На компьютер» (#288): работа буквально одна — [PC_SEND_STAGE].
        reportStage(PC_SEND_STAGE)
        return when (val outcome = transport.send(pc, input, name, input.metadata, action.id)) {
            // #114: «готово» имеет право сказать только тот, кто это сделал. Доставка файла —
            // не выполнение действия: пока компьютер не назвал исход, телефон говорит ровно то,
            // что знает сам, — теми же словами, что и соседнее «На компьютер».
            is PcSendOutcome.Sent -> materialize(outcome) ?: when (val done = outcome.action) {
                null -> ActionResult.Done("Отправлено на компьютер")
                is PcActionOutcome.Done ->
                    // Слова компьютера сильнее наших: «В очереди «HP» · проверьте принтер» честнее
                    // общего «готово», потому что сказано тем, кто печатал.
                    ActionResult.Done(done.detail?.takeIf { it.isNotBlank() } ?: "${action.label} — готово")
                is PcActionOutcome.Failed -> ActionResult.Failure(
                    done.reason.takeIf { it.isNotBlank() }
                        ?: "Компьютер не смог выполнить «${action.label}»",
                    recoverable = true,
                )
            }
            // Соседние отказы об одном и том же событии обязаны звучать одинаково (#524): пока
            // у каждого действия были свои слова, шесть формулировок описывали три события.
            PcSendOutcome.Rejected -> ActionResult.Failure(PC_DEVICE_REVOKED, recoverable = true)
            is PcSendOutcome.Unreachable ->
                ActionResult.Failure(pcUnreachableText(outcome.why), recoverable = true)
        }
    }
}
