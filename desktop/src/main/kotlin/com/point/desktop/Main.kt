package com.point.desktop

import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.point.desktop.ui.DesktopApp
import java.awt.FileDialog
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File

/**
 * Composition root — hand-wired DI (no Hilt on the JVM). AWT implementations of the
 * desktop seams live here and only here; everything below them is pure and tested.
 */
/** Сколько ждать, пока окно Point уйдёт с глаз перед снимком: меньше — и оно попадёт в кадр. */
private const val SCREEN_GRAB_DELAY_MS = 220L

fun main(args: Array<String>) {
    val pointDir = File(System.getProperty("user.home"), ".point-pc")
    // «Отправить в Point» из проводника (#252). Point на компьютере обычно уже открыт, поэтому
    // файл сначала предлагается живому экземпляру — второе окно на каждый пункт меню человеку не
    // нужно. Никого живого нет — открываемся сами с этим файлом.
    val handed = runCatching { SendToRunning.handOff(filesFromArgs(args), pointDir) }.getOrDefault(false)
    if (handed) return
    // Замок держится до конца процесса: по нему следующий запуск и понимает, что мы живы.
    val instanceLock = SendToRunning.takeLock(pointDir)
    val config = FilePcConfig(pointDir).load()
    val inbox = Inbox(File(System.getProperty("user.home"), "Point"))

    val clipboard = TextClipboard { text ->
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
    }
    val opener = SystemOpener { file -> java.awt.Desktop.getDesktop().open(file) }
    // Печать (#291): AWT живёт только здесь, за швом Printer. Уходит на принтер по умолчанию —
    // диалог на компьютере тот, кто тапнул на телефоне, не увидит.
    val printer = object : Printer {
        override fun name(): String? =
            runCatching { javax.print.PrintServiceLookup.lookupDefaultPrintService()?.name }.getOrNull()

        override fun print(file: File) = java.awt.Desktop.getDesktop().print(file)

        /**
         * Системный диалог печати (#591) — принтер, формат, страницы, двусторонняя, чужими руками.
         *
         * `printDialog` возвращает `false`, когда человек закрыл окно: это «передумал», а не отказ,
         * и зовущий скажет об этом словами.
         */
        override fun printAsking(file: File): Boolean = runCatching {
            val job = java.awt.print.PrinterJob.getPrinterJob()
            if (!job.printDialog()) return@runCatching false
            // Печатаем тем же способом, что и без диалога: рисовать документ сами мы не умеем, а
            // выбор человека система запоминает как свой принтер по умолчанию для задания.
            java.awt.Desktop.getDesktop().print(file)
            true
        }.getOrDefault(false)
    }
    val revealer = FileRevealer { file ->
        when {
            System.getProperty("os.name").lowercase().contains("win") ->
                ProcessBuilder("explorer.exe", "/select,", file.absolutePath).start()
            System.getProperty("os.name").lowercase().contains("mac") ->
                ProcessBuilder("open", "-R", file.absolutePath).start()
            else -> java.awt.Desktop.getDesktop().open(file.parentFile)
        }
    }

    lateinit var state: DesktopState
    val saveTarget = SaveTarget { file ->
        val dialog = FileDialog(null as java.awt.Frame?, "Сохранить в…", FileDialog.SAVE)
        dialog.file = file.name
        dialog.isVisible = true
        val dir = dialog.directory ?: return@SaveTarget null
        val name = dialog.file ?: return@SaveTarget null
        val target = File(dir, name)
        file.copyTo(target, overwrite = true)
        target.absolutePath
    }

    // Снимок экрана (#585): AWT-`Robot` живёт здесь, как и весь остальной AWT, — за швом.
    val screenGrab = ScreenGrab(File(System.getProperty("user.home"), "Point/screens"))
    val downloader = YtDlpDownloader(File(System.getProperty("user.home"), "Point/downloads"))
    val outbox = Outbox(File(System.getProperty("user.home"), "Point/outbox"))
    // Чем компьютер умеет рисовать слайды (#403). Ищется один раз при старте: между запусками
    // Office не появляется, а дёргать файловую систему на каждый тап незачем.
    val officeToPdf = LocalOfficeToPdf()
    // Аккаунт этого компьютера (#473). Раньше ПК знал о себе только токен, имя и порт — владельца
    // у него не было вовсе. Вход, круг устройств и пропуск — тем же кодом, что на телефоне.
    val serverUrl = com.point.core.flow.PointServer.base(config.server)
    val accountStore = FileAccountStore(pointDir)
    // Ключи компьютера (#475): закрытая половина не покидает эту машину, открытая едет в круг.
    val deviceKeys = FileDeviceKeys(pointDir)

    // AI на компьютере (#585): ключ читается из конфига при КАЖДОМ вызове, а не один раз при
    // старте, — человек вписывает его, не перезапуская Point.
    val llm = DesktopLlmClient(config = { FilePcConfig(pointDir).load().ai })
    val entities = com.point.core.flow.RegexEntityExtractor()
    val registry = DesktopRegistry(
        setOf(
            // Своё у компьютера — доставки и эффекты: у них место назначения и есть намерение,
            // и слить их с телефонными значило бы разрешить выполнить не то, что человек назвал.
            PcOpenCapability(), PcCopyCapability(), PcRevealCapability(), PcSaveAsCapability(),
            PcDownloadCapability(), PcToPhoneCapability(), PcPrintCapability(),
            PcOpenLinkCapability(),
            // Преобразования, ещё не переехавшие в общий словарь: ждут общего контракта
            // готовности ключей.
            PcUnderstandCapability(), PcTranslateCapability(), PcAskCapability(),
            PcTranscribeCapability(),
            // Эти двое ждут среза 3: у общей декларации `accepts` шире, чем умеет
            // реализация ПК («В PDF» с картинки), а «Собрать данные» судит по
            // признакам, которых компьютер пока не выставляет вовсе.
            // «Собрать данные» ждёт понимания на ПК: оно судит по признакам объекта,
            // а компьютер их пока не выставляет вовсе.
            PcEntitiesCapability(),
        ) +
            // Общий словарь намерений (контракт 06.08.2026, И1): одна декларация на оба
            // устройства, а компьютер даёт им свои реализации.
            com.point.core.flow.capabilities.sharedCapabilities(),
    )
    val resolver = DesktopResolver(
        setOf(
            PcOpenRealizer(opener),
            PcCopyRealizer(clipboard, imageClipboard = ::writeSystemClipboard),
            PcRevealRealizer(revealer),
            PcSaveAsRealizer(saveTarget),
            PcDownloadRealizer(downloader),
            PcToPhoneRealizer(outbox),
            PcPrintRealizer(printer),
            PcOfficePdfRealizer(officeToPdf, outbox),
            PcEntitiesRealizer(entities, outbox),
            PcAiRealizer(
                com.point.core.model.CapabilityId("pc-understand"), llm, PcPrompts.UNDERSTAND,
                outbox, "Понятое",
            ),
            PcAiRealizer(
                com.point.core.model.CapabilityId("pc-translate"), llm, PcPrompts.TRANSLATE,
                outbox, "Перевод",
            ),
            PcAiRealizer(
                com.point.core.model.CapabilityId("pc-ask"), llm, PcPrompts.ASK,
                outbox, "Ответ AI",
            ),
            PcQrRealizer(outbox),
            PcDropRealizer(
                DesktopDropLink(serverUrl) { accountStore.current()?.deviceToken },
                clipboard,
            ),
            PcUnzipRealizer(revealer),
            PcOfficeTextRealizer(com.point.core.flow.OoxmlOfficeTextExtractor(), outbox),
            PcShrinkImageRealizer(outbox),
            PcTranscribeRealizer({ FilePcConfig(pointDir).load().speech }, outbox),
            PcCloudOcrRealizer({ FilePcConfig(pointDir).load().ocr }, outbox),
            PcOpenLinkRealizer { url ->
                runCatching { java.awt.Desktop.getDesktop().browse(java.net.URI(url)) }
            },
        ),
    )
    val accountScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default,
    )
    val account = DesktopAccount(
        scope = accountScope,
        store = accountStore,
        client = com.point.core.flow.HttpAccountClient(serverUrl, deviceKeys.keys().publicKey),
        // Своего окна для чужих страниц у Point нет и не будет — вход открывает системный браузер.
        browser = { url -> runCatching { java.awt.Desktop.getDesktop().browse(java.net.URI(url)) } },
        deviceName = config.name,
        keys = deviceKeys,
    )

    val phoneCapsFile = File(pointDir, "phone-caps")
    // Память о пути объектов (#407) — рядом с остальным состоянием ПК, тем же способом хранения.
    val journalStore = FileJournalStore(File(pointDir, "journal"))
    state = DesktopState(
        registry, resolver, clipboard, outbox,
        persistPhoneCaps = { caps ->
            runCatching { phoneCapsFile.apply { parentFile?.mkdirs() }.writeText(com.point.core.flow.encodePcCaps(caps)) }
        },
        journalStore = journalStore,
        // «Открыть заново» из журнала: файл оборачивается на месте, копии не делается. Нет файла —
        // нет объекта, и экран об этом скажет вместо того, чтобы открыть пустоту.
        reopenPath = { path -> File(path).takeIf(File::isFile)?.let { inbox.addFile(it.absolutePath) } },
    )
    // Уборка при запуске (#602): всё, что Point положил сюда сам и что пролежало сутки, уходит.
    // Тот же срок, что у сервера, и та же причина, что у стирания рабочей копии на телефоне.
    runCatching { inbox.sweep(System.currentTimeMillis() - 24L * 60 * 60 * 1000) }

    runCatching { com.point.core.flow.decodePcCaps(phoneCapsFile.readText()) }
        .getOrNull()?.let(state::setPhoneCaps)

    // Что этот компьютер умеет (#588). Список ВЫВОДИТСЯ из того же реестра, из которого растут
    // действия на самом ПК: добавил способность — она поехала на телефон сама. Ручной перечень,
    // который тут лежал раньше, приходилось дописывать вручную на каждое новое действие, и
    // разойтись с реестром он мог молча.
    //
    // Причины «умею, но не сейчас» (#316) реестр не знает — они про железо и ключи этой машины,
    // и накладываются здесь, поверх выведенного списка.
    val pcSuffix = mapOf(
        "pc-open" to "Открыть на компьютере",
        "pc-copy" to "В буфер компьютера",
        "pc-reveal" to "Показать в папке на ПК",
    )
    val pcUnavailable: Map<String, String?> = mapOf(
        "pc-download" to if (downloader.available()) null else "на компьютере нет yt-dlp",
        // #291: печать отрабатывает, только если система её поддерживает и принтер по умолчанию
        // есть; #316: если нет — говорим, чего именно не хватает.
        "pc-print" to whyCannotPrint(),
        "pc-office-pdf" to officeToPdf.whyUnavailable(),
        // AI-действия приезжают недоступными, пока ключа на компьютере нет: кнопки на телефоне не
        // будет вовсе, и человек не потратит тап на молчание.
        "pc-understand" to aiKeyMissing(pointDir),
        "pc-translate" to aiKeyMissing(pointDir),
        "pc-ask" to aiKeyMissing(pointDir),
        "pc-transcribe" to speechKeyMissing(pointDir),
        // Ссылку выдаёт сервер, значит нужен вход.
        "pc-drop" to if (accountStore.current() != null) null else "компьютер не вошёл в аккаунт",
    )
    val pcRemoteActions = com.point.core.flow.advertisedActions(registry.all()).map { action ->
        action.copy(
            // «на ПК» в имени — не украшение: человек читает список на телефоне, где рядом стоят
            // его собственные действия, и должен видеть, чьё это умение.
            label = pcSuffix[action.id] ?: (action.label + " на ПК"),
            unavailable = pcUnavailable[action.id],
            // Увезёт ли эта реализация объект из круга — спрашивается у резолвера, а не пишется
            // рядом списком: список разошёлся бы с правдой на первой же новой реализации.
            leavesCircle = resolver.leavesDevice(com.point.core.model.CapabilityId(action.id)),
        )
    }

    // Открылись сами: файл из меню становится объектом сразу, без лишнего действия человека.
    filesFromArgs(args).forEach { file ->
        state.onReceived(inbox.addFile(file.absolutePath), ObjectSource.LOCAL)
    }

    // Что компьютер отвечает телефону (#475). Одна дорога — один разбор почты: своего
    // HTTP-сервера у ПК больше нет, входящих соединений он не слушает, и запроса брандмауэра при
    // запуске человек не видит.
    val requests = RelayRequests(
        remoteActions = { pcRemoteActions },
        outbox = outbox,
        onPhoneCaps = state::setPhoneCaps,
        // Ключи сервисов общие для устройств человека (#589): приехали с телефона — слили со
        // своими и вернули общие. Канал уже запечатан ключами устройств, сервер видит шифротекст.
        onSecrets = { theirs -> FilePcConfig(pointDir).mergeSecrets(theirs) },
        clipboardGet = ::readSystemClipboard,
        clipboardSet = ::writeSystemClipboard,
        onObject = { name, mime, meta, bytes, action ->
            val item = inbox.receive(name, mime, meta, bytes.inputStream())
            state.onReceived(item, ObjectSource.PHONE_RELAY)
            // #114: телефон ждёт исход, а не факт доставки, — и теперь ему есть чем ответить и
            // через сервер: письмо с ответом кладётся в его ящик.
            action?.let { state.runRemoteActionNow(it, item) }
        },
        log = { line -> println("[mailbox] " + line) },
    )
    val relayPoller = RelayPoller(
        serverUrl = serverUrl,
        account = { accountStore.current() },
        peers = account::peers,
        secrets = com.point.core.flow.KeyStoreSecrets(deviceKeys),
        requests = requests,
        onContact = state::heard,
        onUnknownSender = account::refreshCircleNow,
        log = { line -> println("[mailbox] " + line) },
    ).also { it.start() }

    // Переданное из проводника живому Point (#252): пути кладут в каталог, мы их подбираем.
    val handOffs = Thread({
        while (true) {
            runCatching {
                SendToRunning.collectHandOffs(pointDir).forEach { file ->
                    state.onReceived(inbox.addFile(file.absolutePath), ObjectSource.LOCAL)
                }
            }
            runCatching { Thread.sleep(1_000) }.getOrElse { return@Thread }
        }
    }, "point-handoff").apply { isDaemon = true }.also { it.start() }

    application {
        // Окно мокапа — 1440x900 (#285). Берём чуть меньше, чтобы влезало и на ноутбучный экран,
        // но так, чтобы конвейер помещался целиком: на 800x600 по умолчанию он не помещался.
        val windowState = androidx.compose.ui.window.rememberWindowState(
            width = 1320.dp, height = 900.dp,
        )
        Window(
            state = windowState,
            onCloseRequest = {
                relayPoller.stop()
                handOffs.interrupt()
                runCatching { instanceLock?.release() }
                exitApplication()
            },
            title = "Point для ПК",
            icon = painterResource("point-icon.png"),
        ) {
            // Language of the portal (#285): the desktop speaks the same palette and type as
            // the phone, so the two stop looking like different products.
            com.point.desktop.ui.PointDesktopTheme {
            // Local input: native Compose drag&drop (the AWT window.dropTarget never fired —
            // the Compose surface intercepts drops; DesktopApp uses Modifier.dragAndDropTarget).
            DesktopApp(
                state = state,
                config = config,
                account = account,
                // Происхождение объекта разделено (#407): перетащенный мышью и взятый из буфера —
                // разные ответы на вопрос «откуда это здесь взялось», и журнал обязан их различать.
                onFilesDropped = { files ->
                    files.forEach { state.onReceived(inbox.addFile(it.absolutePath), ObjectSource.DROPPED) }
                },
                onTextDropped = { text -> state.onReceived(inbox.addText(text), ObjectSource.DROPPED) },
                onClipboardTaken = { text -> state.onReceived(inbox.addText(text), ObjectSource.CLIPBOARD) },
                // Снимок экрана (#585). Окно Point убирается на миг: иначе человек снимет сам
                // Point вместо того, что было под ним.
                onWipe = { inbox.wipe() },
                onGrabScreen = {
                    val was = windowState.isMinimized
                    windowState.isMinimized = true
                    Thread.sleep(SCREEN_GRAB_DELAY_MS)
                    val file = screenGrab.take()
                    windowState.isMinimized = was
                    file
                },
            )
            }
        }
    }
}

/**
 * Почему этот компьютер сейчас не напечатает — или `null`, если напечатает. Реклама действия,
 * которое не отработает, — обещание, которого мы не сдержим (#291); молчание вместо действия
 * человек читает как «Point не умеет печатать» (#316). Поэтому не булево «можно/нельзя», а
 * причина словами: её увидит тот, кто держит телефон в руках.
 */
/** Нет ключа AI — три действия приезжают на телефон недоступными, кнопки там не будет (#316). */
private fun aiKeyMissing(pointDir: java.io.File): String? =
    if (FilePcConfig(pointDir).load().ai.key.isNotBlank()) null else "на компьютере не задан ключ AI"

/** Расшифровке нужен ключ ДРУГОГО сервиса, поэтому и причина у неё своя. */
private fun speechKeyMissing(pointDir: java.io.File): String? =
    if (FilePcConfig(pointDir).load().speech.key.isNotBlank()) {
        null
    } else {
        "на компьютере не задан ключ расшифровки"
    }

private fun whyCannotPrint(): String? {
    val systemPrints = runCatching {
        java.awt.Desktop.isDesktopSupported() &&
            java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.PRINT)
    }.getOrDefault(false)
    if (!systemPrints) return "этот компьютер не умеет печатать"
    // Именно принтер ПО УМОЛЧАНИЮ: печать уходит на него, и если его нет, кнопка обманет.
    val printer = runCatching { javax.print.PrintServiceLookup.lookupDefaultPrintService() }.getOrNull()
    return if (printer == null) "на компьютере нет принтера" else null
}
