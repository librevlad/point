package com.point.desktop

import com.point.core.flow.PcRemoteAction
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Стук не выдаёт себя за пробуждение телефона (#1108).
 *
 * Компьютер просил сервер постучать, сервер отвечал «постучали» — и компьютер писал «ждёт
 * телефона», после чего молчал до вечера. На телефоне при этом не происходило ничего:
 * почта Google берёт письмо и для выключенного телефона, а приложение, остановленное
 * человеком, Android не поднимает ни на какое письмо. Обещание выполнял человек руками,
 * не зная, что оно на нём.
 *
 * Проверяется здесь ровно то, что компьютер говорит человеку, и говорит ли он это, зная:
 *
 * - письма не взяли — про телефон компьютеру не известно ничего, и сном телефона это не
 *   называется;
 * - письмо взяли, а за просьбой пришёл не тот, в кого стучали (в круге бывает второй
 *   компьютер), — тишины по-прежнему нет;
 * - стучать было не во что (компьютер без аккаунта, круг без телефона) — провала нет, и
 *   компьютер его не называет;
 * - объект, уехавший на телефон, — та же правда и в «ПУТЬ», одной строкой: плашка гаснет,
 *   а вопрос «где мой файл» человек задаёт и через час;
 * - телефон, проснувшийся вовремя, опоздавшим не называется — ни на просьбе, ни на уехавшем
 *   объекте: это самый частый исход стука, и человек в нём читает то, что ему делать;
 * - телефон, заговоривший позже срока, отменяет сказанное про свой сон: срок решает, когда
 *   сказать, а не что останется в «ПУТЬ» навсегда;
 * - плашка при этом не отнимается у человека: слово про телефон встаёт на место сказанного
 *   про его ожидание, а не поверх того, что человек читает сейчас;
 * - и журнал не переписывается на диск на каждый кадр телефона: писать нечего, пока в нём
 *   ничего не изменилось.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class KnockDoesNotPretendTest {

    @get:Rule val temp = TemporaryFolder()

    /** Срок ожидания — событие планировщика теста, а не секунды на занятой машине. */
    private val dispatcher = StandardTestDispatcher()

    private val knocks = java.util.concurrent.atomic.AtomicInteger()

    /**
     * Что ответил стук: по каждому телефону круга — взяла ли почта письмо для него.
     *
     * Пусто — стучать было не во что: ни аккаунта, ни телефона в круге.
     */
    private var knocked: Map<String, Boolean> = mapOf(PHONE to true)

    private lateinit var pc: DesktopState

    /**
     * Компьютер целиком, а не одна его половина: дверь «На телефон» на месте, и стук вслед
     * уехавшему объекту подключён так же поздно, как в `Main.kt`, — исполнитель рождается
     * раньше окна.
     */
    private fun state(box: Outbox, journal: JournalStore? = null): DesktopState {
        val toPhone = PcToPhoneRealizer(box, knockPhone = { obj -> pc.knockAfterSending(obj) })
        pc = DesktopState(
            registry = DesktopRegistry(setOf(PcToPhoneCapability())),
            resolver = DesktopResolver(setOf(toPhone)),
            clipboard = { },
            outbox = box,
            journalStore = journal,
            knockPhone = {
                knocks.incrementAndGet()
                knocked
            },
            background = dispatcher,
            io = dispatcher,
        )
        return pc
    }

    private fun item(id: String = "id", name: String = "объект") = InboxItem(
        PointObject(
            id,
            "text/plain",
            ScratchRef(temp.newFile("$name.txt").apply { writeText("+380671234567") }.absolutePath),
            ObjectState(ObjectKind.TEXT),
        ),
    )

    private val call = PcRemoteAction("call", "Позвонить")

    private fun stepsOf(pc: DesktopState, item: InboxItem) =
        pc.journal.value.first { it.path == item.obj.uri.value }.steps

    private fun lastStep(pc: DesktopState, item: InboxItem) = stepsOf(pc, item).last()

    @Test
    fun `телефон не пришёл за просьбой — компьютер говорит, что он не проснулся`() = runTest(dispatcher) {
        val box = Outbox(temp.newFolder("outbox"))
        val pc = state(box)
        val obj = item()
        pc.onReceived(obj)

        pc.sendToPhone(obj, call)
        pc.approvePhone()
        advanceUntilIdle()

        assertEquals("в телефон не постучали", 1, knocks.get())
        assertEquals("компьютер так и не сказал, что телефон не проснулся", PHONE_DID_NOT_WAKE, pc.message.value)

        val step = lastStep(pc, obj)
        assertEquals("шаг молчит про непроснувшийся телефон", PHONE_DID_NOT_WAKE_NOTE, step.note)
        assertEquals("исход у шага взялся из ниоткуда", StepOutcome.AWAITING, step.outcome)

        // Правда про телефон не отменяет просьбу: она дождётся человека на месте.
        assertEquals("просьба пропала вместе с надеждой на стук", 1, box.entries().size)
    }

    /**
     * Удачный исход стука — самый частый: телефон просыпается за секунды и сам идёт за
     * просьбой. Небылицы здесь стоят дороже всего, потому что читает их человек чаще
     * всего, — и небылица про сон не единственная: сказать пришедшему вовремя телефону,
     * что он отозвался позже, чем компьютер его ждал, — та же неправда наизнанку. Ждал
     * компьютер двенадцать секунд, телефон пришёл за три.
     *
     * И цена у неё не только в неправде: на месте этих слов стоит единственная строка,
     * которая говорит человеку, что сейчас делать, — «откройте на телефоне главный экран
     * Point и заберите объект».
     */
    @Test
    fun `телефон пришёл за просьбой — небылиц про него нет`() = runTest(dispatcher) {
        val box = Outbox(temp.newFolder("outbox"))
        val pc = state(box)
        val obj = item()
        pc.onReceived(obj)

        pc.sendToPhone(obj, call)
        pc.approvePhone()
        runCurrent()
        val waiting = pc.message.value

        // Проснувшийся Point идёт за просьбой сам — это и есть голос телефона.
        pc.heard(PHONE)
        advanceUntilIdle()

        assertTrue(
            "телефон отозвался, а компьютер объявил его спящим - ${pc.message.value}",
            PHONE_DID_NOT_WAKE != pc.message.value,
        )
        assertNotEquals(
            "телефон пришёл раньше срока, а назван опоздавшим",
            PHONE_SPOKE_LATE,
            pc.message.value,
        )
        assertEquals(
            "человек лишился строки, которая говорит ему, что делать",
            waiting,
            pc.message.value,
        )
        assertNotEquals("шаг переписан небылицей", PHONE_DID_NOT_WAKE_NOTE, lastStep(pc, obj).note)
        assertNotEquals("шаг переписан небылицей про опоздание", PHONE_SPOKE_LATE_NOTE, lastStep(pc, obj).note)
    }

    /**
     * У объекта, уехавшего по кнопке «На телефон», слово про телефон — последнее: исход
     * работы оттуда не возвращается, и плашка гаснет с тем, что на ней осталось. Неправда,
     * вставшая сюда, так и догорает неправдой, а человек остаётся без строки, которая
     * говорит, что объект ждёт его на телефоне.
     */
    @Test
    fun `уехавший объект — вовремя пришедший телефон не гасит плашку неправдой`() = runTest(dispatcher) {
        val box = Outbox(temp.newFolder("outbox"))
        val pc = state(box)
        val obj = item()
        pc.onReceived(obj)

        val door = pc.bubblesFor(obj).single { it.capabilityId.value == PC_TO_PHONE }
        pc.onBubble(obj, door)
        runCurrent()
        val waiting = pc.message.value

        pc.heard(PHONE)
        advanceUntilIdle()

        assertEquals("вовремя пришедший телефон объявлен опоздавшим", waiting, pc.message.value)
        assertEquals("шаг перестал говорить, чего он ждёт", WAITS_FOR_PHONE, lastStep(pc, obj).note)
    }

    /**
     * Круг — это не один телефон: в нём бывает второй компьютер, и его кадр приходит тем же
     * почтовым ящиком. Считать такой кадр пробуждением телефона значит вернуть человека в ту
     * самую тишину, ради которой всё и затевалось.
     */
    @Test
    fun `отозвался не тот, в кого стучали — тишины по-прежнему нет`() = runTest(dispatcher) {
        val box = Outbox(temp.newFolder("outbox"))
        val pc = state(box)
        val obj = item()
        pc.onReceived(obj)

        pc.sendToPhone(obj, call)
        pc.approvePhone()
        runCurrent()

        pc.heard(SECOND_PC)
        advanceUntilIdle()

        assertEquals("голос второго компьютера сошёл за пробуждение телефона", PHONE_DID_NOT_WAKE, pc.message.value)
        assertEquals("шаг замолчал о телефоне", PHONE_DID_NOT_WAKE_NOTE, lastStep(pc, obj).note)
    }

    /**
     * Ни ключа у сервера, ни адреса у телефона, ни сети у компьютера — исход один: письмо
     * никуда не ушло. Что стало с телефоном, компьютер в этом случае не знает вовсе, и
     * называть человеку сон телефона — утверждать больше, чем знаешь (Конституция, инвариант 8).
     */
    @Test
    fun `письма не взяли — компьютер не выдумывает сон телефона`() = runTest(dispatcher) {
        knocked = mapOf(PHONE to false)
        val box = Outbox(temp.newFolder("outbox"))
        val pc = state(box)
        val obj = item()
        pc.onReceived(obj)

        pc.sendToPhone(obj, call)
        pc.approvePhone()
        advanceUntilIdle()

        assertEquals("названа причина, которой компьютер не знает", COULD_NOT_KNOCK, pc.message.value)
        assertEquals("в журнале причина тоже придумана", COULD_NOT_KNOCK_NOTE, lastStep(pc, obj).note)
        assertEquals("просьба пропала вместе с неудачным стуком", 1, box.entries().size)
    }

    /**
     * Вторая дорога стука — объект, уехавший на телефон по кнопке «На телефон». Дорога у неё
     * своя, а правда обязана быть та же и там же, где человек её ищет: плашка на экране живёт
     * минуту от силы, «ПУТЬ» — всегда (Product Constitution PC3).
     *
     * И строка одна. Шаг, положивший объект в очередь, ждёт телефона, а не сделан: с
     * галочкой «получилось» правда вставала второй строкой того же имени, и первой в «ПУТЬ»
     * оставалось ровно то «ждёт телефона», которое она поправляет.
     */
    @Test
    fun `уехавший объект — правда про телефон встаёт одной строкой в журнал`() = runTest(dispatcher) {
        val box = Outbox(temp.newFolder("outbox"))
        val pc = state(box)
        val obj = item()
        pc.onReceived(obj)

        val door = pc.bubblesFor(obj).single { it.capabilityId.value == PC_TO_PHONE }
        pc.onBubble(obj, door)
        advanceUntilIdle()

        assertEquals("объект не уехал на телефон", 1, box.entries().size)
        assertEquals("в телефон не постучали вслед объекту", 1, knocks.get())
        assertEquals("компьютер промолчал о непроснувшемся телефоне", PHONE_DID_NOT_WAKE, pc.message.value)

        val steps = stepsOf(pc, obj)
        assertEquals("правда о телефоне встала второй строкой рядом с «ждёт телефона» - $steps", 1, steps.size)

        val step = steps.single()
        assertEquals("в журнале навсегда осталось «ждёт телефона»", PHONE_DID_NOT_WAKE_NOTE, step.note)
        assertEquals("исход у шага взялся из ниоткуда", StepOutcome.AWAITING, step.outcome)
        assertEquals("правда легла не к тому действию", PC_TO_PHONE, step.capabilityId)
    }

    /**
     * Ответ стука с компьютера без аккаунта — пустой, а не «ни в один не постучали».
     *
     * Разница эта и есть та, из-за которой человек слышал провал на ровном месте: стука не
     * было вовсе, некуда и не с чем, — и исхода у него нет. Пустой ответ отсюда доходит до
     * человека молчанием (проверка ниже), поэтому цена подмены здесь — небылица на экране.
     */
    @Test
    fun `на компьютере без аккаунта стучать не во что`() = runTest(dispatcher) {
        val letters = java.util.concurrent.atomic.AtomicInteger()
        val account = accountless(letters)

        assertTrue("стук доложил об исходе, которого не было: ${account.knockPhones()}", account.knockPhones().isEmpty())
        assertEquals("компьютер без аккаунта постучал сервером, которому нечем его узнать", 0, letters.get())
    }

    /** Компьютер, на котором человек ещё не вошёл, — рабочее состояние Point (#1022). */
    private fun accountless(letters: java.util.concurrent.atomic.AtomicInteger) = DesktopAccount(
        scope = kotlinx.coroutines.CoroutineScope(dispatcher),
        store = object : com.point.core.flow.AccountStore {
            override fun current(): com.point.core.flow.PointAccount? = null
            override suspend fun save(account: com.point.core.flow.PointAccount) = Unit
            override suspend fun clear() = Unit
        },
        client = object : com.point.core.flow.AccountClient {
            override suspend fun start(
                deviceName: String,
                kind: com.point.core.flow.DeviceKind,
            ): com.point.core.flow.LoginStart? = null

            override suspend fun poll(loginId: String, claimToken: String) = com.point.core.flow.LoginPoll.Silent

            override suspend fun enroll(account: com.point.core.flow.PointAccount, publicKey: String) = false

            override suspend fun circle(account: com.point.core.flow.PointAccount) =
                com.point.core.flow.CircleAnswer.Unreachable

            override suspend fun revoke(account: com.point.core.flow.PointAccount, deviceId: String) = false

            override suspend fun deleteAccount(account: com.point.core.flow.PointAccount) = false

            override suspend fun knock(account: com.point.core.flow.PointAccount, deviceId: String): Boolean {
                letters.incrementAndGet()
                return true
            }
        },
        browser = com.point.core.flow.BrowserOpener { },
        deviceName = "Компьютер",
        keys = object : com.point.core.flow.DeviceKeyStore {
            override fun keys() = com.point.core.flow.DeviceKeyPair(privateKey = "", publicKey = "")
        },
    )

    /**
     * Компьютер без аккаунта — рабочее состояние Point, и кнопка «На телефон» на нём есть.
     * Стучать в этом случае не во что: телефона у круга нет вовсе. Ничего и не срывалось —
     * объект лёг в очередь и ждёт, — а «не удалось постучать» назвало бы человеку провал
     * там, где его не было, да ещё чужой причиной: своей у несуществующего телефона нет.
     */
    @Test
    fun `в круге нет телефона — компьютер не выдумывает неудачный стук`() = runTest(dispatcher) {
        knocked = emptyMap()
        val box = Outbox(temp.newFolder("outbox"))
        val pc = state(box)
        val obj = item()
        pc.onReceived(obj)

        val door = pc.bubblesFor(obj).single { it.capabilityId.value == PC_TO_PHONE }
        pc.onBubble(obj, door)
        advanceUntilIdle()

        assertEquals("объект не лёг в очередь", 1, box.entries().size)
        assertNotEquals("названа неудача стука, которого не было", COULD_NOT_KNOCK, pc.message.value)
        assertNotEquals("названа неудача стука, которого не было", PHONE_DID_NOT_WAKE, pc.message.value)

        val steps = stepsOf(pc, obj)
        assertEquals("в «ПУТЬ» попал исход стука, которого не было - $steps", 1, steps.size)
        assertEquals("шаг перестал говорить, чего он ждёт", WAITS_FOR_PHONE, steps.single().note)
    }

    /**
     * Срок досмотра — решение, когда перестать молчать, а не знание о телефоне. Телефон,
     * заговоривший позже него, делает сказанное про свой сон неправдой — и на место
     * сказанного встаёт то, что случилось. Иначе «телефон не проснулся» осталось бы в «ПУТЬ»
     * навсегда, в том самом месте, куда человек приходит с вопросом «где мой файл» (PC3), —
     * и осталось бы там даже после того, как объект уже на телефоне.
     *
     * Второй компьютер круга при этом по-прежнему не считается за телефон: его кадр про
     * телефон не говорит ничего ни внутри срока, ни после него.
     */
    @Test
    fun `телефон заговорил позже срока — сказанное про его сон меняется вместе с ним`() = runTest(dispatcher) {
        val box = Outbox(temp.newFolder("outbox"))
        val pc = state(box)
        val obj = item()
        pc.onReceived(obj)

        val door = pc.bubblesFor(obj).single { it.capabilityId.value == PC_TO_PHONE }
        pc.onBubble(obj, door)
        advanceUntilIdle()
        assertEquals("компьютер промолчал о непроснувшемся телефоне", PHONE_DID_NOT_WAKE_NOTE, lastStep(pc, obj).note)

        pc.heard(SECOND_PC)
        advanceUntilIdle()
        assertEquals("голос второго компьютера сошёл за поздний приход телефона", PHONE_DID_NOT_WAKE_NOTE, lastStep(pc, obj).note)

        // Телефон заговорил — пусть и позже, чем компьютер его ждал.
        pc.heard(PHONE)
        advanceUntilIdle()

        val steps = stepsOf(pc, obj)
        assertEquals("правда о позднем телефоне встала второй строкой - $steps", 1, steps.size)
        assertEquals("в «ПУТЬ» навсегда осталась неправда про сон телефона", PHONE_SPOKE_LATE_NOTE, steps.single().note)
        assertEquals("шаг перестал ждать телефона, хотя объект всё ещё у него", StepOutcome.AWAITING, steps.single().outcome)
        assertEquals("человеку не сказано, что телефон всё-таки заговорил", PHONE_SPOKE_LATE, pc.message.value)
    }

    /**
     * Плашка одна на всё окно, и человек читает её сейчас. Пока компьютер досматривает стук,
     * человек успевает сделать что-то ещё — взять факт в буфер, получить отказ, принять
     * объект, — и слово про телефон, вставшее поверх, отнимает у него ровно то, на что он
     * смотрит. Правда при этом не пропадает: она ложится в «ПУТЬ», где её и ищут через час
     * (Product Constitution PC3).
     */
    @Test
    fun `исход стука не затирает того, что человек читает сейчас`() = runTest(dispatcher) {
        val box = Outbox(temp.newFolder("outbox"))
        val pc = state(box)
        val obj = item()
        pc.onReceived(obj)

        pc.sendToPhone(obj, call)
        pc.approvePhone()
        runCurrent()

        // Человек не сидит перед плашкой — он взял из объекта телефонный номер.
        pc.copyFact("+380671234567")
        val reading = pc.message.value
        advanceUntilIdle()

        assertEquals("исход стука затёр то, что человек читал", reading, pc.message.value)
        assertEquals("правда о телефоне пропала и из «ПУТЬ»", PHONE_DID_NOT_WAKE_NOTE, lastStep(pc, obj).note)
    }

    /**
     * Слово про телефон знает свой объект (#1337).
     *
     * Путь человека: он отправил на телефон A, через несколько секунд — B, и на плашке стоит
     * «ждёт телефона» про B. Досмотр стука по A кончается позже и говорит «телефон не
     * проснулся» — но это правда про A, а не про B. Пока слово не знало своего объекта, оно
     * вставало над строкой про B, и человек читал вердикт про A как слово про B.
     *
     * Правда про A при этом не теряется: она лежит в «ПУТИ» у A, где записи разведены по
     * объектам, и дожидается там вопроса «где мой файл» (PC3).
     */
    @Test
    fun `вердикт про один объект не встаёт над словом про другой`() = runTest(dispatcher) {
        val box = Outbox(temp.newFolder("outbox"))
        val pc = state(box)
        val first = item("a", "первый")
        val second = item("b", "второй")
        pc.onReceived(first)
        pc.onReceived(second)

        pc.sendToPhone(first, call)
        pc.approvePhone()
        runCurrent()

        // Второй объект уехал следом, на середине досмотра первого: теперь на плашке слово
        // про него, а вердикт по первому ещё в пути.
        advanceTimeBy(PHONE_WAKES_WITHIN_MS / 2)
        pc.sendToPhone(second, call)
        pc.approvePhone()
        runCurrent()
        val aboutSecond = pc.message.value
        assertTrue("на плашке не слово про второй объект: $aboutSecond", aboutSecond!!.contains(WAITS_FOR_PHONE))

        // Досмотр первого кончился; досмотр второго ещё идёт.
        advanceTimeBy(PHONE_WAKES_WITHIN_MS / 2 + 1)
        runCurrent()

        assertEquals(
            "вердикт про первый объект встал над словом про второй",
            aboutSecond,
            pc.message.value,
        )
        assertEquals(
            "правда про первый объект пропала из «ПУТЬ»",
            PHONE_DID_NOT_WAKE_NOTE,
            lastStep(pc, first).note,
        )
    }

    /**
     * И наоборот: вердикт про тот же объект встаёт на место своего же ожидания (#1108).
     *
     * Иначе адресат превратился бы в глушилку: человек, отправивший один объект, перестал бы
     * узнавать о непроснувшемся телефоне вовсе.
     */
    @Test
    fun `вердикт про свой объект встаёт на место его же ожидания`() = runTest(dispatcher) {
        val box = Outbox(temp.newFolder("outbox"))
        val pc = state(box)
        val obj = item()
        pc.onReceived(obj)

        pc.sendToPhone(obj, call)
        pc.approvePhone()
        runCurrent()

        advanceUntilIdle()

        assertEquals("человек не узнал о непроснувшемся телефоне", PHONE_DID_NOT_WAKE, pc.message.value)
    }

    /**
     * Журнал человека переписывается на диск целиком — сорок объектов вместе со знанием
     * каждого. Телефон, ожидающий слова компьютера, тем временем заглядывает в его очередь
     * снова и снова, и каждый такой кадр доходит до досмотра стука. Если менять в журнале
     * нечего, диску тут делать нечего тоже.
     */
    @Test
    fun `журнал ложится на диск, только когда в нём что-то изменилось`() = runTest(dispatcher) {
        val saves = java.util.concurrent.atomic.AtomicInteger()
        val box = Outbox(temp.newFolder("outbox"))
        val pc = state(box, countingJournal(saves))
        val obj = item()
        pc.onReceived(obj)

        pc.sendToPhone(obj, call)
        pc.approvePhone()
        advanceUntilIdle()
        assertEquals("компьютер промолчал о непроснувшемся телефоне", PHONE_DID_NOT_WAKE_NOTE, lastStep(pc, obj).note)

        // Телефон открылся позже срока и пошёл за просьбой: сказанное про его сон устарело.
        val beforeLate = saves.get()
        pc.heard(PHONE)
        advanceUntilIdle()

        assertEquals("правка про поздний телефон не дошла до диска", beforeLate + 1, saves.get())
        assertEquals("поздний приход телефона не поправил «ПУТЬ»", PHONE_SPOKE_LATE_NOTE, lastStep(pc, obj).note)

        // Дальше он заходит за ответом ещё и ещё — а менять в журнале уже нечего.
        val written = saves.get()
        repeat(5) {
            pc.heard(PHONE)
            advanceUntilIdle()
        }

        assertEquals("каждый кадр телефона переписывает весь журнал на диск", written, saves.get())
    }

    /** Журнал, который считает записи на диск и ничего не хранит. */
    private fun countingJournal(saves: java.util.concurrent.atomic.AtomicInteger) = object : JournalStore {
        override fun load(): List<JournalEntry> = emptyList()

        override fun save(entries: List<JournalEntry>) {
            saves.incrementAndGet()
        }
    }

    /**
     * Правда должна встать на место строки «ждёт телефона», пока та ещё на экране. Пока
     * сроки не знали друг о друге, строка гасла раньше — и правда появлялась в пустоте.
     */
    @Test
    fun `правда успевает застать плашку, которую поправляет`() {
        assertTrue(
            "досмотр кончается позже, чем гаснет плашка: $PHONE_WAKES_WITHIN_MS против $MESSAGE_LIVES_MS",
            PHONE_WAKES_WITHIN_MS < MESSAGE_LIVES_MS,
        )
    }

    private companion object {
        const val PHONE = "устройство-телефон"
        const val SECOND_PC = "устройство-второй-компьютер"
    }
}
