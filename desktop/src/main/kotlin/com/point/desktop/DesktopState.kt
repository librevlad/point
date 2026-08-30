package com.point.desktop

import com.point.core.flow.CapabilityRegistry
import com.point.core.flow.Resolver
import com.point.core.flow.knownBy
import com.point.core.model.ActionResult
import com.point.core.model.Bubble
import com.point.core.model.ObjectKind
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch

data class Working(
    val title: String,
    val stage: String?,
    val startedAt: Long,

    /** Чей это шаг: из списка видно, куда вернуться к работе. */
    val objectId: String? = null,

    /** Уходит ли работа наружу: от этого зависит, что честно сказать про ожидание (#901). */
    val network: Boolean = false,

    /**
     * Сама идущая работа — ею же она и прекращается (#1319).
     *
     * Признак работы и её отменяемость — одно свойство одной работы, а не два независимых
     * поля. Пока признак ставился здесь, а «Отменить» гасило отдельное поле, заполненное
     * только на пути клика по этому экрану, кнопка над работой по просьбе телефона
     * обещала и не делала ничего: человек считал, что отменил, а просьба доводилась до
     * конца и уезжала исходом соседу.
     *
     * Показанной, но неотменяемой работы теперь не бывает: на экран её ставит тот самый
     * заход, который её и выполняет.
     */
    val job: kotlinx.coroutines.Job,
)

class DesktopState(
    private val registry: CapabilityRegistry,
    private val resolver: Resolver,
    private val clipboard: TextClipboard,
    private val outbox: Outbox? = null,
    private val persistPhoneCaps: (List<com.point.core.flow.PcRemoteAction>) -> Unit = {},

    private val journalStore: JournalStore? = null,

    private val clock: Clock = Clock { System.currentTimeMillis() },

    private val reopenPath: (String) -> InboxItem? = { null },

    internal val consent: com.point.core.flow.PrivacyConsent? = null,

    /** Выбранный человеком режим отправки: спрашивается у настроек, а не помнится копией. */
    private val privacyLevel: () -> com.point.core.flow.PrivacyLevel =
        { com.point.core.flow.PrivacyLevel.DEFAULT },

    /** Прибытие объявляется наружу (peek-плашка): и с телефона, и готовое здесь. */
    private val announce: (InboxItem, ObjectSource) -> Unit = { _, _ -> },

    /**
     * Исполняет ли телефон просьбы компьютера (#785, включено в #817).
     *
     * Причина, по которой это было выключено, оказалась неверной. Она говорила: просьба
     * поедет почтой и будет стёрта чисткой ящика. На деле просьба почтой не едет — она
     * ложится в папку `outbox` на диске самого компьютера, а телефон сам спрашивает «что у
     * тебя для меня». Спрашивает он, значит стирать некому.
     *
     * Телефон при этом давно умеет выполнять названное действие: объект приходит с
     * `pc.action`, и `FlowViewModel` делает его сразу после приёма. Работа была сделана и
     * просто выключена флагом.
     *
     * Чтобы просьба не ждала случайного открытия Point, компьютер просит сервер постучать
     * в телефон. Стук несёт одно слово «зайди»; чего именно от него хотят, телефон
     * спрашивает у компьютера напрямую.
     */
    internal val phoneRunsRequests: Boolean = true,

    /**
     * Постучать в телефон: «зайди, для тебя что-то есть» (#817).
     *
     * Молчание не ломает работу: без ключа, без разрешения на уведомления и без сети
     * просьба всё равно дождётся — просто человек узнает о ней, открыв Point сам.
     */
    private val knockPhone: suspend () -> Unit = {},

    /**
     * Чем исполняется фоновая работа окна. В работе это общий пул, под тестом —
     * планировщик теста: тогда «исследование доведено до конца» проверяется как
     * состоявшееся событие, а не как истёкший срок в секундах: на занятой машине
     * срок кончается раньше работы, и тест краснеет на здоровом коде.
     */
    private val background: CoroutineDispatcher = Dispatchers.Default,

    /**
     * Чем исполняется работа с диском и сетью: очередь телефону, чтение и запись файлов,
     * стук в телефон. В работе это дисковый пул; под тестом сюда передают тот же
     * планировщик, что и в [background], и тогда «просьба легла в очередь» — состоявшееся
     * событие, которого дожидаются, а не срок, который истёк.
     */
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val scope = CoroutineScope(SupervisorJob() + background)

    private val _items = MutableStateFlow<List<InboxItem>>(emptyList())
    val items: StateFlow<List<InboxItem>> = _items.asStateFlow()

    private val _journal = MutableStateFlow(runCatching { journalStore?.load() }.getOrNull().orEmpty())

    val journal: StateFlow<List<JournalEntry>> = _journal.asStateFlow()

    private val _working = MutableStateFlow<Working?>(null)

    val working: StateFlow<Working?> get() = _working.asStateFlow()

    /**
     * Человек прекращает ту работу, которую видит (#1319), — кем бы она ни была начата:
     * рукой за этим компьютером или просьбой телефона. Отдельного поля «что отменять»
     * больше нет: отменяется работа, стоящая на экране.
     */
    fun cancelWork() {
        _working.value?.job?.cancel()
    }

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _clipboardText = MutableStateFlow<String?>(null)

    val clipboardText: StateFlow<String?> = _clipboardText.asStateFlow()

    private val _phoneCaps = MutableStateFlow<List<com.point.core.flow.PcRemoteAction>>(emptyList())

    val phoneCaps = _phoneCaps.asStateFlow()

    private val _lastContact = MutableStateFlow<Long?>(null)
    val lastContact: StateFlow<Long?> = _lastContact.asStateFlow()

    fun heard() {
        _lastContact.value = clock.now()
    }

    fun bubblesFor(item: InboxItem): List<Bubble> {

        // Тот же вывод уместного смысла, что и на телефоне: из знания объекта (ADR-0001 §14).
        val graph = com.point.core.flow.GraphState(item.obj)
        return registry.bubblesFor(graph.copy(intent = com.point.core.flow.leadingIntent(graph)))
    }

    fun say(text: String) { _message.value = text }

    /** Факт забирается в буфер одним кликом — на ПК буфер и есть главная валюта (P4). */
    fun copyFact(value: String) {
        runCatching { clipboard.copy(value) }
        _message.value = "В буфере: $value"
    }

    /** Человеческое имя вопроса знания; вопросы без имени на экран не выходят (P2). */
    fun questionName(id: com.point.core.model.CapabilityId, state: com.point.core.model.ObjectState): String? =
        runCatching { registry.byId(id).label(state) }.getOrNull() ?: PHONE_QUESTIONS[id.value]

    fun runRemoteAction(id: String, item: InboxItem) {
        scope.launch { perform(id, item) }
    }

    /**
     * Бюджет здесь — про синхронный ответ телефону, не про работу (телефон ждёт
     * ответа считанные секунды). Долгое действие не обрывается: оно доводится в
     * scope компьютера, а готовый результат уезжает существующей очередью
     * ПК→телефон вместе со знанием (Product Constitution PC2/PC4). Телефону сразу
     * уходит честное «ещё работаю» вместо ложного «отправлено».
     *
     * Отменённая человеком просьба — такой же исход, как любой другой (#1319): телефону
     * она возвращается тем же путём (#1073), отказом с объявленным словом отмены. Работа,
     * прерванная на полпути, не родила ничего, и молчание про неё оставило бы телефону
     * обещание «компьютер ещё работает» навсегда.
     */
    fun runRemoteActionNow(id: String, item: InboxItem, budgetMs: Long = 10_000): ActionResult? {
        val work = kotlinx.coroutines.CompletableDeferred<ActionResult?>()
        scope.launch {
            val outcome = try {
                perform(id, item)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {

                // Человек прекратил показанную работу — это отказ, а не пропавшая просьба.
                ActionResult.Failure(com.point.core.flow.PC_CANCELLED, recoverable = true)
            } catch (broken: Throwable) {

                // Прочее — беда операции: исход назовёт общий путь ниже, как и раньше.
                null
            }
            work.complete(outcome)
        }
        val quick = kotlinx.coroutines.runBlocking {
            kotlinx.coroutines.withTimeoutOrNull(budgetMs) { work.await() }
        }
        if (quick != null) return quick

        scope.launch {
            val late = runCatching { work.await() }.getOrNull()
            runCatching {
                if (late is ActionResult.Success) {
                    outbox?.add(
                        com.point.core.model.PointObject(
                            id = java.util.UUID.randomUUID().toString(),
                            mime = late.result.mime,
                            uri = late.result.uri,
                            state = com.point.core.model.ObjectState(late.result.type),

                            // Долгая работа возвращается тем же объектом, что и быстрая
                            // (#1112): из чего сделан, чем сделан и каким путём. Прежде
                            // медленный результат приезжал на телефон сиротой с
                            // происхождением «дано» — как будто его прислал человек.
                            metadata = late.result.metadata + com.point.core.flow.lineageMeta(
                                sourceId = homeOf(item),
                                creator = id,
                                provenance = late.result.provenance,
                                executor = PC_EXECUTOR,
                            ),
                            sourceObjects = listOf(item.obj.id),
                            creatorAction = id,
                            provenance = late.result.provenance,
                        ),
                    )
                } else {
                    // Исход без объекта — «Отменено» у диалога, «готово» словами, отказ —
                    // едет телефону той же очередью (#1073). Прежде ехал только файл, и
                    // обещание «ещё работает» висело на телефоне вечно: компьютер отмену
                    // записал в журнал, а сказать о ней соседу было нечем.
                    outbox?.addOutcome(lateOutcomeMeta(id, item, late))
                }
            }.onFailure {
                val why = "Результат не лёг в очередь для телефона — проверьте, что на диске есть место"
                _message.value = why
                note(item, id, titleOf(id, item) + " · результат в очередь", ActionResult.Failure(why, recoverable = true))
            }
        }
        return ActionResult.Done(com.point.core.flow.PC_STILL_WORKING)
    }

    /** Каким именем объект знает телефон: просьба приехала с ним, результат по нему найдёт дом. */
    private fun homeOf(item: InboxItem): String =
        item.obj.metadata[com.point.core.flow.META_ORIGIN_ID] ?: item.obj.id

    /**
     * Поздний исход просьбы телефона — словами домой (#1073): чей объект, как просьба
     * называлась и чем кончилась. Понятое компьютером едет теми же полями, что и в срочном
     * ответе (PC2).
     *
     * Название просьбы едет ради слов человеку: отказ у телефона говорит, к чему он
     * относится. Больше в записи нет ничего — того, чего никто не читает, здесь не кладут.
     *
     * Понятое отправляется тем же правилом, что и в срочном ответе (`packedForTravel`, #1097):
     * прочитанный текст лежит файлом здесь, и путь к нему на телефоне не открывается. Медленная
     * расшифровка попадает телефону именно отсюда — бюджет ответа 10 с, а сервис отвечает
     * дольше, — и без замены телефон получал неоткрываемый путь вместе с закрытым вопросом:
     * текста у человека нет, а повторно предложить расшифровку уже некому.
     */
    private fun lateOutcomeMeta(id: String, item: InboxItem, late: ActionResult?): Map<String, String> {
        val f = com.point.core.flow.PcResultFields
        val e = com.point.core.flow.PcExecFields
        val outcome = com.point.core.flow.pcActionOutcomeOf(late)
            // Работа кончилась ничем — ни исходом, ни объектом: телефону это срыв, а не тишина.
            ?: com.point.core.flow.PcActionOutcome.Failed(DID_NOT_RUN)
        val understood = packedForTravel((late as? ActionResult.Done)?.findings?.metadata.orEmpty())
            .mapKeys { (k, _) -> f.UNDERSTOOD + k }
        return mapOf(
            e.HOME to homeOf(item),
            e.LABEL to phoneFacingLabel(id, titleOf(id, item)),
        ) + f.of(outcome) + understood
    }

    companion object {

        private const val DID_NOT_RUN = "Действие не выполнилось — попробуйте ещё раз"

        /** Как компьютер называет себя в происхождении знания (#1127). */
        const val PC_EXECUTOR = "pc"

        /** Имена вопросов, заданных другой поверхностью: её capability здесь не зарегистрированы. */
        private val PHONE_QUESTIONS = mapOf(
            "image-text" to "Текст на снимке",
            "qr-content" to "QR-код",
            "understand" to "Понимание",
            "entities" to "Контакты и номера",
        )
    }

    /**
     * Почему это действие сейчас наружу не пойдёт — словами выбранного режима (#893).
     *
     * Одно правило в одном месте — и для клика по экрану, и для просьбы соседа (#1269).
     *
     * Мерка здесь строже телефонной, и намеренно. Телефон спрашивает «пускает ли режим
     * наружу хоть кого-нибудь» (`anyoneAllowedAt`, #945) и ниже по цепочке отбирает
     * читателей по обещанию: до сервиса, не обещавшего не учиться на присланном, объект у
     * него не доедет. На компьютере такого отбора нет — цепочка здесь ничьих обещаний не
     * несёт, и её приватность ровно `AI_CHAIN_PRIVACY`. Спросить телефонную мерку значило
     * бы в режиме «Не учатся на моём» отправить объект туда, где этого никто не обещал:
     * приватность важнее удобства.
     */
    private fun wayOutClosed(id: com.point.core.model.CapabilityId): String? {
        if (!runCatching { resolver.leavesDevice(id) }.getOrDefault(false)) return null
        val level = privacyLevel()
        if (com.point.core.flow.allowedAt(level, com.point.core.flow.AI_CHAIN_PRIVACY)) return null
        return com.point.core.flow.chainClosedBy(level)
    }

    /**
     * Один путь исполнения на оба входа — рука человека и автоматическое исследование (#1272).
     *
     * [quiet] — тихий заход. От громкого он отличается только тем, чего человек не заказывал:
     * не показывает индикатор операции, не обнуляет сказанное до него и не пишет шаг в «ПУТЬ»
     * (телефон сорвавшиеся исследования в журнал тоже не пишет — знанием они не стали). Про
     * удачу тихий заход молчит: добытое говорит за себя фактами объекта.
     *
     * Не молчит он об одном — о беде. Отказ, съеденный тишиной, ничем не отличается от
     * исследования, которого не было (Конституция, инвариант 8), а то же исследование, нажатое
     * рукой, о своём отказе докладывает.
     */
    private suspend fun perform(
        id: String,
        item: InboxItem,
        stationTitle: String? = null,
        quiet: Boolean = false,
    ): ActionResult? {
        val title = stationTitle ?: titleOf(id, item)
        val step = if (stationTitle != null) title else "$title · с телефона"

        // Одна воронка на все входы (#1269): режим проверяется здесь, а не только на пути
        // клика по экрану. Просьба телефона была единственным входом мимо него — человек
        // закрыл компьютеру дорогу наружу, а компьютер по просьбе соседа всё равно
        // отправлял страницы чужому сервису (Конституция §11).
        //
        // Тихий заход выходит отсюда так же, как из любого другого отказа (#1272): причина
        // человеку названа словами исследования, автошага в «ПУТЬ» нет.
        wayOutClosed(com.point.core.model.CapabilityId(id))?.let { why ->
            val closed = ActionResult.Failure(why, recoverable = false)
            _message.value = if (quiet) com.point.core.flow.investigationFailedNote(why) else why
            if (!quiet) note(item, id, step, closed)
            return closed
        }
        if (!quiet) {
            _message.value = null
            _working.value = Working(
                title,
                stage = null,
                startedAt = clock.now(),
                objectId = item.obj.id,
                network = runCatching {
                    resolver.leavesDevice(com.point.core.model.CapabilityId(id))
                }.getOrDefault(false),

                // Показанная работа несёт себя саму (#1319): «Отменить» гасит именно тот
                // заход, который человек сейчас видит, а не последний, начатый кликом.
                job = currentCoroutineContext().job,
            )
        }
        val result = try {
            runCatching {

                kotlinx.coroutines.withContext(
                    com.point.core.flow.ActionProgress { stage ->

                        // Тихий заход не трогает индикатор — в том числе чужой: рядом может
                        // идти нажатое человеком действие, и его стадия не его дело.
                        if (!quiet) _working.value = _working.value?.copy(stage = stage)
                    } +

                        com.point.core.flow.RequestOrigin(here = quiet || stationTitle != null),
                ) {
                    val realizer =
                        resolver.realizerFor(com.point.core.model.CapabilityId(id), item.obj.state)

                    // Кто добыл знание, знает только этот шов (#1273): исследование видит свой
                    // вопрос, а не того, кем он решён в этот раз. Тот же шов, что у телефона в
                    // `DefaultEnrichment`.
                    realizer.perform(item.obj, null).knownBy(item.obj, realizer.meta.actor)
                }
            }.getOrElse { e ->

                if (e is kotlinx.coroutines.CancellationException) throw e
                if (e is NoWayHere) {
                    ActionResult.Failure(e.why, recoverable = false)
                } else {
                    ActionResult.Failure(DID_NOT_RUN, recoverable = true)
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            if (!quiet) {
                _working.value = null
                _message.value = "Отменено"
                note(item, id, title, ActionResult.Failure("отменено", recoverable = true))
            }
            throw e
        } finally {
            if (!quiet) _working.value = null
        }

        if (result is ActionResult.Success) {
            val born = runCatching { reopenPath(result.result.uri.value) }.getOrNull()
            if (born != null) {
                val named = result.result.metadata["name"]
                val item2 = if (named.isNullOrBlank()) {
                    born
                } else {
                    born.copy(obj = born.obj.copy(metadata = born.obj.metadata + ("name" to named)))
                }
                onReceived(item2, ObjectSource.LOCAL)
            }
        }

        // Знание из шага ложится в сам объект — тем же mergeKnowledge, что и на телефоне
        // (Конституция §4: обогащение не создаёт версию объекта; аудит 2026-08-09, блок 1.1).
        val findings = (result as? ActionResult.Done)?.findings
        if (findings != null && !findings.isEmpty) landFindings(item, findings)
        _message.value = when {

            // Отказ не скрывается ни на одном заходе (#1272). Тихому нужны ещё и слова про то,
            // что это было: человек ничего не нажимал, и одна голая причина ему не адресована.
            result is ActionResult.Failure && quiet ->
                com.point.core.flow.investigationFailedNote(result.reason)

            result is ActionResult.Failure -> result.reason

            // Про удачу тихий заход молчит: знание видно фактами, а рождённый объект — сам
            // собой, своим появлением в списке.
            quiet -> _message.value

            result is ActionResult.Done -> result.message
            result is ActionResult.Success -> result.result.metadata["name"] ?: "Готово"
            else -> _message.value
        }

        // Автоматический шаг в «ПУТЬ» не пишется (решение владельца 23.08.2026): журнал —
        // память о сделанном человеком, и телефон сорвавшиеся исследования тоже не помнит.
        if (!quiet) note(item, id, step, result)
        return result
    }

    /**
     * Телефон исполнил просьбу и вернул результат домой (ADR-0001 §7, §20).
     *
     * Дом объекта не менялся: работа ушла к соседу, а результат материализуется здесь, у
     * исходного объекта — тем же путём, каким это делает шаг, исполненный на месте. Второй
     * машины состояний для этого не заводится: то же `landFindings`, тот же `onReceived`,
     * та же запись шага в журнале.
     */
    fun onExecutionResult(
        homeId: String,
        meta: Map<String, String>,

        /** Файл результата, уже принятый на диск, — или `null`, если работа его не родила. */
        born: InboxItem?,
    ): ActionResult {
        val item = _items.value.firstOrNull { it.obj.id == homeId }
            ?: return ActionResult.Failure("объекта уже нет на компьютере", recoverable = false)
        val f = com.point.core.flow.PcResultFields
        val action = meta[com.point.core.flow.PcExecFields.ACTION].orEmpty()
        val label = meta[com.point.core.flow.PcExecFields.LABEL]?.takeIf { it.isNotBlank() }
            ?: titleOf(action, item)

        val understood = meta
            .filterKeys { it.startsWith(f.UNDERSTOOD) }
            .mapKeys { (k, _) -> k.removePrefix(f.UNDERSTOOD) }
        if (understood.isNotEmpty()) {
            landFindings(item, arrivedKnowledge(item, understood))
        }

        if (meta[f.OUTCOME] == f.FAILED) {
            val why = meta[f.DETAIL]?.takeIf { it.isNotBlank() } ?: "телефон не смог выполнить «$label»"
            _message.value = why
            note(item, action, "$label · на телефоне", ActionResult.Failure(why, recoverable = true))
            return ActionResult.Failure(why, recoverable = true)
        }

        // «Ждёт» исходом не является (ADR-0001 §18): шаг не кончился и не сорвался — там
        // спрашивают человека. Прежде это приезжало как «не вышло», и на компьютере поверх
        // честного «ждёт телефона» ложился провал работы, которая ещё не начиналась (#1269).
        if (meta[f.OUTCOME] == f.AWAITING) {
            val why = meta[f.DETAIL]?.takeIf { it.isNotBlank() } ?: "«$label» ждёт продолжения на телефоне"
            _message.value = why
            noteAwaiting(item, action, "$label · на телефоне", why)
            return ActionResult.NeedsInput(why)
        }

        // Объект, рождённый работой соседа, ложится сюда как любой другой результат — со
        // своей родословной, а не новой вещью неизвестного происхождения.
        if (born != null) {
            val lineage = com.point.core.flow.withLineage(born.obj, meta).copy(
                sourceObjects = listOf(item.obj.id),
                creatorAction = action.takeIf { it.isNotBlank() },
            )
            onReceived(born.copy(obj = lineage), ObjectSource.PHONE_RELAY)
        }
        val detail = meta[f.DETAIL]?.takeIf { it.isNotBlank() } ?: "$label — готово"
        _message.value = detail
        note(item, action, "$label · на телефоне", ActionResult.Done(detail))
        return ActionResult.Done(detail)
    }

    /**
     * Прочитанное телефоном ложится здесь файлом рядом с объектом (#811, #995).
     *
     * Тот же приём, что и у объекта с телефона (`Inbox`): текст приезжает значением, потому
     * что ссылка на scratch телефона здесь мертва. Без этого просьба «прочитай у себя»
     * возвращалась пустой — компьютер снова считал свой документ непрочитанным.
     *
     * Место у знания одно и то же, кто бы его ни клал (#995): `keepTextBesideDocument`.
     */
    private fun arrivedKnowledge(
        item: InboxItem,
        understood: Map<String, String>,
    ): com.point.core.model.Findings {
        val arrived = com.point.core.flow.textArrivedFromTravel(understood)
        val kept = arrived?.let { text ->
            keepTextBesideDocument(java.io.File(item.obj.uri.value), text)?.absolutePath
        }
        return com.point.core.flow.knowledgeArrivedFromTravel(understood, kept)
    }

    private fun landFindings(item: InboxItem, findings: com.point.core.model.Findings) {
        val current = _items.value.firstOrNull { it.obj.id == item.obj.id } ?: item
        val newState = findings.features.fold(current.obj.state) { state, feature -> state.with(feature) }
        val newMeta = com.point.core.flow.mergeKnowledge(
            current.obj.metadata,
            findings.metadata,
            com.point.core.flow.REFRESHABLE_KNOWLEDGE,
        )
        if (newState == current.obj.state && newMeta == current.obj.metadata) return
        val updated = current.copy(obj = current.obj.copy(state = newState, metadata = newMeta))
        _items.update { list -> list.map { if (it.obj.id == item.obj.id) updated else it } }

        // findings.objects (узлы-сущности) появятся на экране ПК в фазе B редизайна.
        updateJournal { recordKnowledge(it, updated.obj.uri.value, newMeta) }
    }

    fun setPhoneCaps(caps: List<com.point.core.flow.PcRemoteAction>, persist: Boolean = true) {

        // Загрузка кэша с диска не пишет его обратно: иначе метка времени файла
        // выглядит свежей, хотя телефон мог не объявляться неделю (#624).
        _phoneCaps.value = caps
        if (persist) runCatching { persistPhoneCaps(caps) }
    }

    fun phoneActionsFor(item: InboxItem): List<com.point.core.flow.PcRemoteAction> {
        val mine = registry.all().map { it.id.value }.toSet()
        return _phoneCaps.value.filter { action ->

            // Одно правило применимости на обе стороны (#1092): вид × признаки.
            with(com.point.core.flow.PcActionFit) { action.fitsObject(item.obj.state) } && action.id !in mine
        }
            // Одно умение может объявить несколько дверей (#1174) — на экране оно одно.
            .distinctBy { it.id }
    }

    /** Одно действие единого списка: здешнее или телефонное, порядок — по пользе (P10). */
    data class ActionChoice(
        val title: String,
        val onPhone: Boolean,
        val unavailable: String? = null,
        val bubble: Bubble? = null,
        val remote: com.point.core.flow.PcRemoteAction? = null,

        /**
         * Тот же значок, что у этого действия на телефоне. Одно действие с двумя разными
         * лицами на двух экранах — два продукта, а не один.
         */
        val icon: String = bubble?.icon.orEmpty().ifBlank { "ai" },
    )

    /**
     * Единый список действий: свои и телефонные ранжируются вместе — по смыслу и пользе,
     * а не по тому, чей реестр их родил (аудит, блок 2.3). Недоступное телефонное видно
     * с причиной, а не скрыто (PC5: возможности дорастают на глазах).
     */
    fun actionsFor(item: InboxItem): List<ActionChoice> {
        val graph = com.point.core.flow.GraphState(item.obj)
        val intent = com.point.core.flow.leadingIntent(graph)
        val here = bubblesFor(item).map { bubble ->
            val capability = runCatching { registry.byId(bubble.capabilityId) }.getOrNull()
            Triple(
                ActionChoice(bubble.title, onPhone = false, bubble = bubble, icon = bubble.icon),
                capability?.meta?.priority ?: com.point.core.flow.PC_CAP_DEFAULT_PRIORITY,
                intent != null && capability != null && intent in capability.intents(item.obj.state),
            )
        }
        val phone = phoneActionsFor(item).map { action ->
            Triple(
                ActionChoice(
                    action.label,
                    onPhone = true,

                    // Причина видна до нажатия, а не после (#785): человек не должен
                    // узнавать о границе связки, ткнув в действие и подождав напрасно.
                    unavailable = when {
                        !phoneRunsRequests -> PHONE_DOES_NOT_RUN_REQUESTS
                        else -> action.unavailable?.ifBlank { "телефон сейчас не может это сделать" }
                    },
                    remote = action,
                    icon = "phone",
                ),
                action.priority,
                false,
            )
        }
        return (here + phone)
            .sortedWith(
                compareBy(
                    { (_, _, servesIntent) -> if (servesIntent) 0 else 1 },
                    { (choice, _, _) -> if (choice.unavailable == null) 0 else 1 },
                    { (_, priority, _) -> priority },
                    { (choice, _, _) -> choice.title },
                ),
            )
            .map { it.first }
    }

    /**
     * Вопрос до дела: телефон, который ответит через час, не может быть выбран за спиной
     * (срез 5 контракта связки, #611). Живой телефон выбирается молча — как любой свой
     * исполнитель; молчащий становится выбором человека: подождать или отказаться.
     */
    data class PhoneAsk(
        val item: InboxItem,
        val action: com.point.core.flow.PcRemoteAction,
        val title: String,
        val what: String,
    )

    /** Ожидание файла по ссылке — на компьютере оно тоже есть (#727). */
    private val _receiving = MutableStateFlow<ReceiveOnPc.Waiting?>(null)
    val receiving: StateFlow<ReceiveOnPc.Waiting?> = _receiving.asStateFlow()

    fun showReceiving(waiting: ReceiveOnPc.Waiting?) { _receiving.value = waiting }

    private val _phoneAsk = MutableStateFlow<PhoneAsk?>(null)
    val phoneAsk: StateFlow<PhoneAsk?> = _phoneAsk.asStateFlow()

    fun sendToPhone(item: InboxItem, action: com.point.core.flow.PcRemoteAction) {

        // Страховка на случай вызова мимо списка действий (#785).
        if (!phoneRunsRequests) {
            _message.value = PHONE_DOES_NOT_RUN_REQUESTS
            return
        }
        val link = com.point.core.flow.linkStateOf(_lastContact.value, clock.now())
        if (link !is com.point.core.flow.LinkState.Live) {
            _phoneAsk.value = PhoneAsk(
                item = item,
                action = action,
                title = "«${action.label}» делает телефон, а он сейчас не на связи",
                // Просьба лежит здесь, на этом компьютере, — не «в почте»: телефон сам
                // придёт за ней, когда человек его откроет (#817).
                what = "Просьба подождёт здесь и выполнится, когда вы откроете Point на телефоне " +
                    "и заберёте объект. Пока этого не случилось, здесь ничего не изменится.",
            )
            return
        }
        queueForPhone(item, action)
    }

    /** Согласился ждать — просьба ложится в почту телефона. */
    fun approvePhone() {
        val ask = _phoneAsk.value ?: return
        _phoneAsk.value = null
        queueForPhone(ask.item, ask.action, silent = true)
    }

    /** Отказ не наказывает: действие остаётся доступным на потом. */
    fun declinePhone() {
        _phoneAsk.value = null
        _message.value = "Ничего не отправлено — объект остался на компьютере. Действие доступно, если передумаете"
    }

    private fun queueForPhone(
        item: InboxItem,
        action: com.point.core.flow.PcRemoteAction,
        silent: Boolean = false,
    ) {
        val request = java.util.UUID.randomUUID().toString()
        scope.launch(io) {
            runCatching {
                outbox?.add(
                    item.obj.copy(
                        metadata = item.obj.metadata +
                            ("pc.action" to action.id) +
                            // Название работы человеческими словами кладёт компьютер: он его
                            // и показывал человеку. Телефону иначе неоткуда взять слова для
                            // уведомления, а звать реестр ради названия — лишний путь.
                            ("pc.action.label" to action.label) +

                            // Дом объекта здесь, телефон — исполнитель (ADR-0001 §7).
                            // Прежде просьба выглядела переездом- телефон открывал объект у
                            // себя, делал работу и там же её оставлял, а на компьютере шаг
                            // ждал вечно. Теперь по этим полям результат возвращается домой.
                            (com.point.core.flow.PcExecFields.ACTION to action.id) +
                            (com.point.core.flow.PcExecFields.LABEL to action.label) +
                            (com.point.core.flow.PcExecFields.REQUEST to request) +
                            (com.point.core.flow.PcExecFields.HOME to item.obj.id),
                    )
                )
            }.onSuccess {
                _message.value = if (silent) {
                    "${action.label} — ждёт телефона: выполнится, когда вы его откроете"
                } else {
                    "${action.label} — ждёт телефона: откройте на телефоне главный экран Point и заберите объект"
                }

                // Шаг поставлен в очередь, а не выполнен (#1112): исхода у него ещё нет, и
                // галочка «получилось» здесь была неправдой — на компьютере ничего не появилось.
                noteAwaiting(item, action.id, "${action.label} · ждёт телефона", "ждёт телефона")
                runCatching { knockPhone() }
            }.onFailure {
                _message.value = "Не удалось положить в очередь"
                note(
                    item, action.id, "${action.label} · на телефон",
                    ActionResult.Failure("Не удалось отправить — проверьте, что на диске есть место", recoverable = true),
                )
            }
        }
    }

    /** Непросмотренные прибытия: след живёт, пока человек не открыл объект (PC3). */
    private val _fresh = MutableStateFlow<Set<String>>(emptySet())
    val fresh: StateFlow<Set<String>> = _fresh.asStateFlow()

    fun markSeen(objectId: String) {
        _fresh.update { it - objectId }
    }

    /**
     * Родить объект из принесённого файла — не останавливая окно (#995).
     *
     * Рождение объекта читает файл: у PDF — целиком, потому что признак «текст файлом не
     * достаётся» судит весь документ, как и исполнитель. Звали его прямо из обработчика
     * броска и из входа в ребёнка набора — то есть тем самым потоком, который рисует окно:
     * толстый PDF останавливал окно до конца чтения. Чтение уходит на дисковый шов, объект
     * приходит в ленту сам — тем же прогрессивным пониманием, что и на телефоне.
     */
    fun receive(source: ObjectSource, born: () -> InboxItem?) {
        scope.launch(io) {
            val item = runCatching { born() }.getOrNull() ?: return@launch
            onReceived(item, source)
        }
    }

    /** Открыть файл по пути — ребёнок набора становится объектом (#1099). */
    fun openPath(path: String) = receive(ObjectSource.LOCAL) { reopenPath(path) }

    fun onReceived(item: InboxItem, source: ObjectSource = ObjectSource.LOCAL) {

        // Тот же объект, присланный второй раз, — возврат к нему, а не вторая копия
        // (#1027, тем же правилом, что вход в открытый объект на телефоне — #1110).
        // Тождество даёт происхождение: письмо помнит, чей это объект. Знание прежнего
        // приезда не теряется — новое ложится поверх обычным merge.
        val origin = item.obj.metadata[com.point.core.flow.META_ORIGIN_ID]
        val twin = origin?.let { known ->
            _items.value.firstOrNull { it.obj.metadata[com.point.core.flow.META_ORIGIN_ID] == known }
        }
        val arrived = if (twin == null) {
            item
        } else {
            item.copy(
                obj = item.obj.copy(
                    metadata = com.point.core.flow.mergeKnowledge(twin.obj.metadata, item.obj.metadata),
                ),
            )
        }
        @Suppress("NAME_SHADOWING") val item = arrived
        _items.update { listOf(item) + it.filterNot { held -> held === twin } }
        _fresh.update { it + item.obj.id }
        rememberArrival(item, source)
        runCatching { announce(item, source) }

        // Буфер обмена компьютера — вещь человека, а не место для приходящего (#1093).
        // Прежде любой текст, приехавший с телефона, молча переписывал то, что человек
        // за компьютером только что скопировал себе. Текст держится наготове, а в буфер
        // попадает по просьбе: «В буфер компьютера» с телефона или кнопка здесь.
        if (item.obj.state.kind == ObjectKind.TEXT) {
            _clipboardText.value = runCatching { File(item.obj.uri.value).readText() }.getOrNull()
        }
        _message.value = "Получено: ${item.obj.metadata["name"]}"
        autoInvestigate(item)
    }

    /**
     * Прибывший объект сразу продолжает цикл понимания (Конституция §9, §11): дешёвое
     * локальное исследование — без клика и без индикации операции. Облачные исполнители
     * сюда не попадают: автоматизм не пересекает границу устройств.
     *
     * Единообразно, а не по одному жёстко зашитому id (владелец, 10.08.2026): любая
     * Capability компьютера с `investigation = true`, подходящая объекту, подключается
     * сама — новой способности обогащения на ПК не нужна отдельная правка здесь.
     *
     * Идёт оно общим путём [perform] тихим заходом (#1272). Своей укороченной дороги здесь
     * больше нет: она глотала и отказ исполнителя, и «нечем это сделать» от резолвера —
     * сорвавшееся исследование выглядело как не начатое.
     */
    private fun autoInvestigate(item: InboxItem) {
        val questions = registry.all()
            .filter { it.meta.investigation && it.accepts(item.obj.state) }
            .map { it.id }
        questions.forEach { question ->
            val asked = com.point.core.flow.investigationStateOf(item.obj.metadata, question)
            if (asked != com.point.core.flow.InvestigationState.NOT_INVESTIGATED) return@forEach
            scope.launch {

                // Внешний Realizer не выбирается молча (ADR-0001 §19). Спрашивается объявленный
                // уход наружу, а не вид исполнителя (#1088): среди своих устройств выбор может
                // быть автоматическим, за круг — только с согласия. Исполнителя здесь только
                // спрашивают о дороге: если его нет вовсе, молчать об этом нельзя — пусть общий
                // путь назовёт человеку причину.
                val outside = runCatching {
                    resolver.realizerFor(question, item.obj.state).meta.leavesCircle
                }.getOrDefault(false)
                if (outside) return@launch
                perform(question.value, item, quiet = true)
            }
        }
    }

    /** Вопрос согласия в момент выбора: объект уходит с устройств только после «да» (P11). */
    data class CloudAsk(
        val item: InboxItem,
        val bubble: Bubble,
        val scope: com.point.core.flow.CloudScope,
        val title: String,
        val destination: String,
        val confirm: String,
    )

    private val _cloudAsk = MutableStateFlow<CloudAsk?>(null)
    val cloudAsk: StateFlow<CloudAsk?> = _cloudAsk.asStateFlow()

    fun onBubble(item: InboxItem, bubble: Bubble) {
        scope.launch(io) {

            // Действие само знает, что сейчас не сработает (#1022): человек слышит причину
            // по тапу, а не после согласия на отправку, которая всё равно не состоится.
            // То же правило и теми же словами, что на телефоне.
            val stopper = runCatching {
                registry.byId(bubble.capabilityId).wontWorkNow(item.obj.state)
            }.getOrNull()
            if (stopper != null) {
                _message.value = stopper
                return@launch
            }
            val guard = consent
            if (guard != null && resolver.leavesDevice(bubble.capabilityId)) {
                // Выбранный человеком режим спрашивается ДО согласия: если он сказал
                // «только на этом устройстве», спрашивать «отправить?» уже поздно и
                // нечестно — объект туда не поедет в любом случае (#893).
                wayOutClosed(bubble.capabilityId)?.let { why ->
                    _message.value = why
                    return@launch
                }
                val needed = com.point.core.flow.cloudScopeOf(bubble.capabilityId)
                val ok = runCatching { guard.allowed(needed) }.getOrDefault(false)
                if (!ok) {
                    _cloudAsk.value = CloudAsk(
                        item, bubble, needed,
                        title = com.point.core.flow.cloudAskTitle(needed),
                        destination = com.point.core.flow.cloudDestination(bubble.capabilityId),
                        confirm = com.point.core.flow.cloudAskConfirm(needed),
                    )
                    return@launch
                }
            }
            perform(bubble.capabilityId.value, item, bubble.title)
        }
    }

    fun approveCloud() {
        val ask = _cloudAsk.value ?: return
        _cloudAsk.value = null
        scope.launch(io) {
            runCatching { consent?.allow(ask.scope) }
            perform(ask.bubble.capabilityId.value, ask.item, ask.bubble.title)
        }
    }

    fun declineCloud() {
        _cloudAsk.value = null

        // Отказ не наказывает: действие остаётся доступным на потом (P11).
        _message.value = "Ничего не отправлено — объект остался на компьютере. Действие доступно, если передумаете"
    }

    /**
     * Клик по истории всегда отвечает: живым объектом ленты, переоткрытым файлом
     * или честным «файла больше нет». Молчание выглядело мёртвой кнопкой
     * (живой прогон 2026-08-09) — выбор возвращённого делает вызвавший экран, [onOpen].
     *
     * Переоткрытие читает файл — у PDF весь, потому что признак «текст файлом не достаётся»
     * судит весь документ. Звали его прямо из обработчика нажатия по строке «Недавнего», то
     * есть потоком, который рисует окно: толстый PDF останавливал окно до конца чтения
     * (Конституция: первый экран без I/O). Дверь рождения объекта здесь та же, что у броска
     * и у входа в ребёнка набора, — и шов у неё тот же дисковый. Живой объект ленты отвечает
     * сразу: он уже здесь, и диск для ответа не нужен.
     */
    fun openAgain(entry: JournalEntry, onOpen: (InboxItem) -> Unit) {
        val live = _items.value.firstOrNull { it.obj.uri.value == entry.path }
        if (live != null) {
            onOpen(live)
            return
        }
        scope.launch(io) {
            val reopened = runCatching { reopenPath(entry.path) }.getOrNull()
            if (reopened == null) {

                _message.value = "Файла больше нет: ${entry.name}"
                return@launch
            }

            // Переоткрытый файл — тот же объект: журнальное знание и имя возвращаются к нему
            // (PC2/PC5). Признак «текст прочитан» в журнал не ложится — он свойство состояния;
            // его возвращает та же улика, что и хранится, — файл с текстом (#995). Заодно это
            // отвечает и на обратный случай: улику убрали из своей папки — знания больше нет,
            // и дверь «Извлечь текст» рисуется снова, а не молчит над пустотой.
            val known = reopened.obj.copy(metadata = reopened.obj.metadata + entry.meta)
            val item = reopened.copy(
                obj = com.point.core.flow.knowledgeOfReadText(known) { java.io.File(it).isFile },
            )
            _items.update { listOf(item) + it }
            onOpen(item)
        }
    }

    fun pathOf(item: InboxItem): JournalEntry? =
        _journal.value.firstOrNull { it.path == item.obj.uri.value }

    private fun rememberArrival(item: InboxItem, source: ObjectSource) {
        val file = java.io.File(item.obj.uri.value)
        updateJournal {
            recordArrival(
                it,
                JournalEntry(
                    path = item.obj.uri.value,
                    name = item.obj.metadata["name"] ?: file.name,
                    kind = item.obj.state.kind.name,
                    mime = item.obj.mime,
                    source = source,
                    at = item.receivedAt,
                    meta = item.obj.metadata,
                ),
            )
        }
    }

    private fun note(item: InboxItem, capabilityId: String, title: String, result: ActionResult) {
        updateJournal {
            recordStep(it, item.obj.uri.value, stepOf(capabilityId, title, clock.now(), result))
        }
    }

    /** Шаг ушёл на телефон и ждёт его: исхода нет, и журнал говорит именно это (#1112). */
    private fun noteAwaiting(item: InboxItem, capabilityId: String, title: String, note: String) {
        updateJournal {
            recordStep(it, item.obj.uri.value, awaitingStep(capabilityId, title, clock.now(), note))
        }
    }

    private fun titleOf(id: String, item: InboxItem): String =
        runCatching { registry.byId(com.point.core.model.CapabilityId(id)).label(item.obj.state) }
            .getOrDefault(id)

    @Synchronized
    private fun updateJournal(transform: (List<JournalEntry>) -> List<JournalEntry>) {
        val next = transform(_journal.value)
        _journal.value = next
        runCatching { journalStore?.save(next) }
    }

    fun copyClipboardAgain() {
        _clipboardText.value?.let { runCatching { clipboard.copy(it) } }
    }

    fun forget(entry: JournalEntry) {
        val file = java.io.File(entry.path)
        if (entry.source != ObjectSource.DROPPED) runCatching { file.delete() }
        _items.update { list -> list.filterNot { it.obj.uri.value == entry.path } }
        updateJournal { it.filterNot { e -> e.path == entry.path } }
    }

    /**
     * Убрать всё, что Point помнит здесь, и сказать сколько (#1081).
     *
     * Прежде «Убрать прямо сейчас» трогало только файлы старше суток и молчало: человек
     * нажимал кнопку, на экране не менялось ничего, а на диске оставалось всё, что он
     * назвал бы памятью Point. Перетащенный мышью файл не трогается никогда — он не наш.
     */
    fun forgetEverything(wipeFiles: () -> Long): Int {
        val kept = _journal.value.size
        val freed = runCatching { wipeFiles() }.getOrDefault(0L)
        _items.value = emptyList()
        _clipboardText.value = null
        updateJournal { emptyList() }
        _message.value = com.point.core.flow.forgottenText(
            com.point.core.flow.HistoryFootprint(kept, freed),
        )
        return kept
    }

    fun clearClipboard() {
        _clipboardText.value = null
    }

    fun dismissMessage() {
        _message.value = null
    }
}

/**
 * Принесённые файлы становятся объектами: пачка — одним объектом-коллекцией с детьми, как на
 * телефоне (#1099), одиночный файл — собой.
 *
 * Правило живёт здесь, а не в обработчике броска: окно исполняет его тем же вызовом, каким
 * проверяет тест. Рождение объекта читает файл — у PDF весь — и потому идёт не потоком окна
 * (#995).
 */
fun DesktopState.receiveFiles(inbox: Inbox, paths: List<String>, source: ObjectSource) {
    if (paths.size > 1) {
        receive(source) { inbox.addFiles(paths) }
    } else {
        paths.forEach { path -> receive(source) { inbox.addFile(path) } }
    }
}

/**
 * Что компьютер честно говорит про работу, которую делает телефон (#785).
 *
 * Прежде здесь стояло обещание «просьба подождёт в его почте и выполнится, когда вы
 * откроете Point на телефоне». Не выполнялась никогда: телефон стирал её `Mailbox.drain`
 * при первом же своём обращении к серверу. Обещание было хуже отсутствия действия —
 * человек ждал результата, которого никто не собирался делать.
 */
const val PHONE_DOES_NOT_RUN_REQUESTS = "телефон пока не выполняет просьбы с компьютера"
