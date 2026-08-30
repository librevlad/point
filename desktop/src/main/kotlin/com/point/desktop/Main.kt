package com.point.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.painterResource
import com.point.desktop.ui.drawPointMark
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import java.awt.FileDialog
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File

private const val SCREEN_GRAB_DELAY_MS = 220L

/**
 * Окно выходит вперёд на каждый явный зов (#1019, решение владельца 20.08.2026, вариант B):
 * и скрытое, и уже видимое, но погребённое под чужими окнами. Зов, случившийся раньше, чем
 * окно собрано (запуск с файлом), не пропадает — исполняется первым кадром. Сам подъём —
 * дело окна ОС, здесь только «когда».
 */
@Composable
internal fun RaiseOnCall(raise: RaiseSignal, bringToFront: () -> Unit) {
    val calls by raise.calls.collectAsState()
    LaunchedEffect(calls) {
        if (calls > 0) bringToFront()
    }
}

fun main(args: Array<String>) {
    val pointDir = File(System.getProperty("user.home"), ".point-pc")

    val handed = runCatching { SendToRunning.handOff(filesFromArgs(args), pointDir) }.getOrDefault(false)
    if (handed) return

    val instanceLock = SendToRunning.takeLock(pointDir)
    val config = FilePcConfig(pointDir).load()
    val inbox = Inbox(File(System.getProperty("user.home"), "Point"))

    val clipboard = TextClipboard { text ->
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
    }
    val opener = SystemOpener { file -> java.awt.Desktop.getDesktop().open(file) }

    // Браузер компьютера — одна дверь на всех, кому он нужен (#1087): «Открыть» со ссылкой,
    // «Открыть в браузере» и вход в аккаунт зовут её же. Отказ дверь не гасит — иначе
    // «Браузер не открылся» никогда не доходит до человека.
    val browse = SystemBrowser()

    val printer = object : Printer {
        override fun name(): String? =
            runCatching { javax.print.PrintServiceLookup.lookupDefaultPrintService()?.name }.getOrNull()

        override fun print(file: File) = java.awt.Desktop.getDesktop().print(file)

        override fun printAsking(file: File): Boolean = runCatching {
            val job = java.awt.print.PrinterJob.getPrinterJob()
            if (!job.printDialog()) return@runCatching false

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

    val compactVisible = kotlinx.coroutines.flow.MutableStateFlow(true)

    // Явный зов из проводника: окно видимо и один раз выходит вперёд (#1019, вариант B).
    // Прежде зов делал ровно compactVisible = true — скрытое показывалось, но не
    // поднималось, а видимое под чужими окнами не менялось вообще.
    val raise = RaiseSignal()
    fun summon() {
        compactVisible.value = true
        raise.call()
    }

    // Просьба человека «побудь открытым»: без неё окно уходит по потере фокуса и
    // принести в него файл мышью нечем — за файлом человек уходит в проводник (#546).
    val openRequest = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val peek = PeekState { System.currentTimeMillis() }
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

    val screenGrab = ScreenGrab(File(System.getProperty("user.home"), "Point/screens"))
    val downloader = YtDlpDownloader(File(System.getProperty("user.home"), "Point/downloads")) { files ->

        // Скачанное приходит в ленту объектом; пусто — честный отказ со следом (PC3/P9).
        if (files.isEmpty()) {
            state.say("Видео не скачалось — подробности в ~/Point/downloads/yt-dlp.log")
        } else {
            files.forEach { state.onReceived(inbox.addFile(it.absolutePath), ObjectSource.LOCAL) }
        }
    }
    val outbox = Outbox(File(System.getProperty("user.home"), "Point/outbox"))

    val officeToPdf = LocalOfficeToPdf()

    val serverUrl = com.point.core.flow.PointServer.base(config.server)
    val accountStore = FileAccountStore(pointDir)

    val deviceKeys = FileDeviceKeys(pointDir)

    val entities = com.point.core.flow.RegexEntityExtractor()

    // Сетевая ли способность — знание живёт у самой способности (#855): исполнители
    // «Понять», «Перевести», «Дать ссылку» называют себя местными, хотя отдают байты наружу.
    var pcCloudReader: PcCloudOcrRealizer? = null
    val capabilities = desktopCapabilities { accountStore.current() != null }

    // Аккаунт рождается ниже исполнителей; стук подключается, как только он есть (#1079).
    var knockPhoneLate: suspend () -> Unit = {}
    val resolver = DesktopResolver(
        realizers = setOf(
            PcOpenRealizer(opener, browse),
            PcCopyRealizer(clipboard, imageClipboard = ::writeSystemClipboard),
            PcRevealRealizer(revealer),
            PcSaveAsRealizer(saveTarget),
            PcDownloadRealizer(downloader),
            PcToPhoneRealizer(outbox, knockPhone = { knockPhoneLate() }),
            PcPrintRealizer(printer),
            PcOfficePdfRealizer(officeToPdf),
            PcPdfTextRealizer(PdfBoxText()),
            PcEntitiesRealizer(entities),
            PcQrRealizer(outbox),
            PcDropRealizer(
                DesktopDropLink(serverUrl) { accountStore.current()?.deviceToken },
                clipboard,
            ),
            PcUnzipRealizer(revealer),
            PcOfficeTextRealizer(com.point.core.flow.OoxmlOfficeTextExtractor()),
            PcShrinkImageRealizer(outbox),
            PcTranscribeRealizer({ speechCall(FilePcConfig(pointDir).load()) }),
            PcCloudOcrRealizer({ FilePcConfig(pointDir).load().ocr }, entities).let { cloudReader ->
                pcCloudReader = cloudReader
                cloudReader
            },
            PcReadDocumentRealizer(readPage = { page -> pcCloudReader!!.readFrame(page, "image/png") }),
            PcOpenLinkRealizer(browse),
        ),
        capabilityIsNetwork = { id -> capabilities.any { it.id == id && it.meta.network } },
    )
    // Приём файла по ссылке на компьютере (#727): разговор с сервером — общий с телефоном.
    val receiver = ReceiveOnPc(
        inbox = com.point.core.flow.HttpDropInbox(
            { serverUrl },
            { accountStore.current()?.deviceToken },

            // След сорвавшегося вызова — сюда, а не человеку на экран (#1077).
            log = { what, e -> println("[drop] " + what + ": " + e) },
        ),
        scope = kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO,
        ),
        tmpDir = java.io.File(pointDir, "received").apply { mkdirs() },
        onArrived = { name, mime, path ->
            val item = inbox.receive(name, mime, emptyMap(), java.io.File(path).inputStream())
            state.onReceived(item, ObjectSource.DROPPED)
        },
    )
    receiver.onWaiting = { state.showReceiving(it) }

    val registry = DesktopRegistry(
        capabilities,

        // Дверь видна, только когда за ней есть исполнитель под этот объект (аудит, блок 1.6).
        runnable = resolver::canRun,
    )
    val accountScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default,
    )
    val account = DesktopAccount(
        scope = accountScope,
        store = accountStore,
        client = com.point.core.flow.HttpAccountClient(serverUrl, deviceKeys.keys().publicKey),

        // Вход в аккаунт браузером не держится: код и адрес человек видит на экране Point,
        // поэтому здесь отказ двери ожидание не обрывает (#1087).
        browser = { url -> runCatching { browse.open(url) } },
        deviceName = config.name,
        keys = deviceKeys,
        mySettings = { FilePcConfig(pointDir).accountSettings() },
        onSettings = { merged -> runCatching { FilePcConfig(pointDir).applyAccountSettings(merged) } },
    )

    val phoneCapsFile = File(pointDir, "phone-caps")

    val journalStore = FileJournalStore(File(pointDir, "journal"))

    // Настройка спрашивается на каждом звуке, а не запоминается: выключили — замолчал сразу.
    val portalSound = JvmPortalSound { FilePcConfig(pointDir).load().sound }
    state = DesktopState(
        registry, resolver, clipboard, outbox,
        persistPhoneCaps = { caps ->
            runCatching { phoneCapsFile.apply { parentFile?.mkdirs() }.writeText(com.point.core.flow.encodePcCaps(caps)) }
        },
        journalStore = journalStore,
        knockPhone = account::knockPhones,

        reopenPath = { path -> File(path).takeIf(File::isFile)?.let { inbox.addFile(it.absolutePath) } },
        consent = FileConsent(File(pointDir, "consent")),

        // Режим спрашивается на каждом действии, как и звук: поменяли — подействовало сразу.
        privacyLevel = { FilePcConfig(pointDir).load().privacy },

        // «Скинули с телефона — высветилась часть окна»: своя плашка, не системное
        // уведомление. Готовое здесь (PDF, скачанное) объявляется тем же путём.
        announce = { item, source ->
            peek.arrived(item, compactVisible.value, source)

            // Звучит только прилёт с телефона (#650): пара к свипу ухода на той стороне.
            // Своё, здешнее и принесённое мышью, звука не просит — оно и так на глазах.
            if (source == ObjectSource.PHONE_RELAY) portalSound.arrived()
        },
    )
    knockPhoneLate = account::knockPhones

    val shellMenu = RegistryShellMenu()
    val sendTo = ShortcutSendToMenu()
    val installedExe = { installedExecutable(ProcessHandle.current().info().command().orElse(null)) }
    runCatching {
        val exe = installedExe()
        if (exe != null && FilePcConfig(pointDir).load().rightClick) {
            val wanted = shellCommandFor(exe)
            if (shellMenuNeedsUpdate(shellMenu.registeredCommand(), wanted)) {
                // При старте человек ничего не нажимал: сбой остаётся следом в логе (#1082).
                // Непрочитанный реестр сбоем записи не называется — и в логе тоже.
                when (shellMenu.register(wanted, SHELL_MENU_TITLE)) {
                    false -> println("[shell-menu] пункт меню файла в реестр не встал")
                    null -> println("[shell-menu] реестр не прочитался — про пункт меню файла не известно")
                    true -> Unit
                }
            }

            // «Отправить → Point» — другое меню Windows и живёт своей записью (#255).
            // След тот же, что у реестра выше: молчание Windows про ярлык записано молчанием,
            // а не «не встал», — иначе про ярлык в логе не остаётся ничего (#1082).
            if (shellMenuNeedsUpdate(sendTo.target(), exe.absolutePath)) {
                when (sendTo.register(exe)) {
                    false -> println("[shell-menu] ярлык «Отправить → Point» не встал")
                    null -> println("[shell-menu] Windows не ответила про ярлык «Отправить → Point»")
                    true -> Unit
                }
            }
        }
    }

    // Правда о пункте меню — в реестре и в папке «Отправить», а не в памяти экрана (#1082):
    // настройки читают её при показе, и после перезапуска переключатель говорит то, что есть,
    // а не то, что когда-то нажали. Очередь на реестр и папку «Отправить» живёт здесь, у самого
    // действия: экран настроек уходит и возвращается, а начатая запись — нет.
    val rightClick = RightClickSwitch(shellMenu, sendTo, installedExe)

    // Беда говорит словами, а не именем класса (#822): системное окно `Error` с
    // `CompactAppKt$CompactApp$18$2$11$1$1` человеку не объясняет ничего. След остаётся на
    // диске рядом с настройками, человеку достаётся фраза и совет.
    Thread.setDefaultUncaughtExceptionHandler { _, error ->
        keepTrouble(pointDir, error)
        runCatching { state.say(troubleWords(error)) }
    }

    // Брошенное на компьютере забывается само и по одному сроку (#1317, решение владельца
    // 29.08.2026): и то, что легло в папку, и то, что ждёт телефона в очереди.
    forgetAbandoned(inbox, outbox, System.currentTimeMillis())

    runCatching { com.point.core.flow.decodePcCaps(phoneCapsFile.readText()) }
        .getOrNull()?.let { state.setPhoneCaps(it, persist = false) }

    // Недоступность спрашивается в момент вопроса, а не замораживается при старте (#1092):
    // человек вошёл в аккаунт, включил принтер, вписал ключ — телефон видит это без
    // перезапуска компьютера, потому что объявление собирается на каждый запрос заново.
    fun pcUnavailableNow(): Map<String, String?> = mapOf(
        "pc-download" to if (downloader.available()) null else "на компьютере нет yt-dlp",

        "pc-print" to whyCannotPrint(),
        "pdf" to officeToPdf.whyUnavailable(),

        "transcribe" to speechKeyMissing(pointDir),

        // «Дать ссылку» отсюда ушла (#1022, #1034): правило «без аккаунта выдавать некому»
        // живёт у самой способности и звучит по тапу на том устройстве, где нажали, — а
        // соседом эта способность больше не исполняется, и объявлять телефону нечего.
    )
    val pcBaseActions = phoneFacingActions(registry.all()).map { action ->
        action.copy(leavesCircle = resolver.leavesDevice(com.point.core.model.CapabilityId(action.id)))
    }
    fun pcRemoteActionsNow(): List<com.point.core.flow.PcRemoteAction> {
        val unavailable = pcUnavailableNow()

        // Режим спрашивается тут же, а не помнится копией (#1269): человек закрыл дорогу
        // наружу — телефон видит причину в самом объявлении, до тапа.
        return withWayOutClosed(
            pcBaseActions.map { it.copy(unavailable = unavailable[it.id]) },
            FilePcConfig(pointDir).load().privacy,
        )
    }

    // Запуск с файлом — такой же явный зов (#1019): окно выходит вперёд. Сам объект экран
    // открывает как любое прибытие: принятое до его сборки — не «уже виденное» (DSK-001).
    val fromArgs = filesFromArgs(args)
    fromArgs.forEach { file -> state.onReceived(inbox.addFile(file.absolutePath), ObjectSource.LOCAL) }
    if (fromArgs.isNotEmpty()) summon()

    val requests = RelayRequests(
        remoteActions = { pcRemoteActionsNow() },
        outbox = outbox,
        onPhoneCaps = state::setPhoneCaps,

        onSecrets = { theirs -> FilePcConfig(pointDir).mergeSecrets(theirs) },
        clipboardGet = ::readSystemClipboard,
        clipboardSet = ::writeSystemClipboard,
        onObject = { name, mime, meta, bytes, action, askedAgoMs ->

            // Результат просьбы возвращается ДОМОЙ, к своему объекту, а не приезжает новой
            // вещью (ADR-0001 §7): дом объекта не менялся, телефон был исполнителем.
            val home = meta[com.point.core.flow.PcExecFields.HOME]?.takeIf { it.isNotBlank() }
            if (home != null) {
                val born = if (com.point.core.flow.PcResultFields.hasObject(meta) && bytes.isNotEmpty()) {
                    inbox.receive(name, mime, meta - com.point.core.flow.PC_EXEC_META, bytes.inputStream())
                } else {
                    null
                }
                state.onExecutionResult(home, meta, born)
            } else {
                val item = inbox.receive(name, mime, meta, bytes.inputStream())
                state.onReceived(item, ObjectSource.PHONE_RELAY)
                action?.let { state.runRemoteActionNow(it, item, askedAgoMs = askedAgoMs) }
            }
        },
        log = { line -> println("[mailbox] " + line) },
        seen = SeenLetters(File(pointDir, "seen-letters")),
    )
    // Круг загружается при старте, а не после первого сбоя: пустые peers делали
    // каждое письмо «не открылось» до удачного refreshCircleNow (2026-08-09).
    account.refreshCircle()

    val relayPoller = RelayPoller(
        serverUrl = serverUrl,
        account = { accountStore.current() },
        peers = account::peers,
        secrets = com.point.core.flow.KeyStoreSecrets(deviceKeys),
        requests = requests,

        // Скачанное письмо ложится сюда до того, как приём подтверждён серверу, и
        // лежит, пока не разобрано: падение на разборе не стоит человеку объекта (#680).
        letters = com.point.core.flow.KeptLetters(File(pointDir, "letters")),
        onContact = state::heard,
        onUnknownSender = account::refreshCircleNow,
        log = { line -> println("[mailbox] " + line) },
    ).also { it.start() }

    val handOffs = Thread({
        while (true) {
            // «Открыть в Point» — человек сам позвал: окошко выходит само и вперёд.
            // Вторая копия не живёт: она отдаёт принесённое этой и уходит.
            runCatching {
                SendToRunning.serveHandOffs(
                    pointDir,
                    receive = { file -> state.onReceived(inbox.addFile(file.absolutePath), ObjectSource.LOCAL) },
                    summon = ::summon,
                )
            }
            runCatching { Thread.sleep(1_000) }.getOrElse { return@Thread }
        }
    }, "point-handoff").apply { isDaemon = true }.also { it.start() }

    // Рабочая область (без таскбара) — в координатах интерфейса.
    val workArea = runCatching {
        val b = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
        ScreenArea(b.x, b.y, b.width, b.height)
    }.getOrDefault(ScreenArea(0, 0, 1280, 720))

    application {
        val visible by compactVisible.collectAsState()
        val compact = compactBounds(workArea)

        // Компакт живёт у трея: закрыть = спрятаться, выход — из меню трея.
        // Непросмотренное прибытие оставляет след на иконке (PC3): peek легко пропустить.
        val freshIds by state.fresh.collectAsState()
        val icon = androidx.compose.runtime.remember(freshIds.isNotEmpty()) {
            pointGlyph(badge = freshIds.isNotEmpty())
        }
        Tray(
            icon = icon,
            tooltip = if (freshIds.isEmpty()) "Point" else "Point — есть новое",
            onAction = { compactVisible.value = true },
            menu = {
                Item("Открыть Point") { compactVisible.value = true }

                Item("Выход") {
                    relayPoller.stop()
                    handOffs.interrupt()
                    runCatching { instanceLock?.release() }
                    exitApplication()
                }
            },
        )

        val compactState = androidx.compose.ui.window.rememberWindowState(
            position = androidx.compose.ui.window.WindowPosition(compact.x.dp, compact.y.dp),
            size = androidx.compose.ui.unit.DpSize(compact.width.dp, compact.height.dp),
        )
        Window(
            visible = visible,
            state = compactState,
            onCloseRequest = { compactVisible.value = false },
            undecorated = true,
            transparent = true,
            resizable = false,

            // Обычное окно не висит поверх чужой работы (владелец 12.08.2026: «сделай
            // десктопное окно нормальным»). Поверх всех оно было честно ровно до тех пор,
            // пока само исчезало по уходу человека.
            alwaysOnTop = false,
            title = "Point",
            icon = androidx.compose.runtime.remember { pointGlyph() },
        ) {
            // «Поверх всех» не возвращается (#1019); если Windows вместо подъёма мигнёт
            // панелью задач — это принятый системный предел.
            RaiseOnCall(raise) {
                window.toFront()
                window.requestFocus()
            }
            com.point.desktop.ui.PointDesktopTheme {
                com.point.desktop.ui.CompactApp(
                    state = state,
                    config = config,
                    account = account,
                    openObject = openRequest,
                    onObjectOpened = { openRequest.value = null },
                    onFilesDropped = { files ->
                        state.receiveFiles(inbox, files.map { it.absolutePath }, ObjectSource.DROPPED)
                    },
                    onTextDropped = { text -> state.onReceived(inbox.addText(text), ObjectSource.DROPPED) },

                    // Картинка со страницы приходит пикселями, а не файлом: чтобы стать
                    // объектом, ей нужен свой файл — снимок ложится в ту же папку (#546).
                    onImageDropped = { picture ->
                        val png = java.io.ByteArrayOutputStream()
                        val written = runCatching {
                            javax.imageio.ImageIO.write(picture, "png", png)
                        }.getOrDefault(false)
                        if (!written) {
                            state.say("Картинку не удалось сохранить — сохраните её на диск и бросьте файлом")
                        } else {
                            state.onReceived(
                                inbox.receive("Картинка.png", "image/png", emptyMap(), png.toByteArray().inputStream()),
                                ObjectSource.DROPPED,
                            )
                        }
                    },
                    onClipboardTaken = { text -> state.onReceived(inbox.addText(text), ObjectSource.CLIPBOARD) },
                    onReceiveFile = { receiver.start { why -> state.say(why) } },
                    onCancelReceive = { receiver.cancel() },
                    onWipe = { inbox.wipe() },
                    onSaveSettings = { changed ->
                        runCatching { FilePcConfig(pointDir).save(changed) }

                        // Выбранное человеком уезжает сейчас, а не при следующем обновлении
                        // круга (#1085): иначе экран говорит одно, а сервер и телефон знают
                        // другое.
                        runCatching { account.syncSettings() }
                    },

                    // Меню Windows трогается по тапу его выключателя, а не каждым сохранением
                    // настроек: сюда не приходит буква имени компьютера. Человек нажал сам,
                    // поэтому «встал ли пункт» уезжает ответом в настройки — переключатель не
                    // говорит «Показывается» поверх пустого реестра (#1082). Выключение отвечает
                    // по эффекту тоже — снято ли: константа «снято» была бы тем же молчанием.
                    onRightClick = rightClick::set,
                    rightClickHolds = rightClick::holds,
                    // «Убрать прямо сейчас» убирает всё, что Point помнит здесь, а не только
                    // файлы старше суток (#1081): само по себе старое по-прежнему уходит при
                    // запуске, а кнопка делает то, что на ней написано.
                    onSweepNow = { state.forgetEverything { inbox.wipe() } },
                    onGrabScreen = {
                        compactVisible.value = false
                        Thread.sleep(SCREEN_GRAB_DELAY_MS)
                        val file = screenGrab.take()
                        compactVisible.value = true
                        file
                    },
                    onHide = { compactVisible.value = false },
                )
            }
        }

        // «Высветилась часть окна»: плашка на месте будущего компакта, сама гаснет по сроку.
        val peekTick by peek.pulse.collectAsState()
        val shown = peekTick?.let { peek.current() }
        if (shown != null && !visible) {
            LaunchedEffect(peekTick) {
                kotlinx.coroutines.delay(PEEK_LIFETIME_MS + 100)
                peek.current()
            }
            val place = peekBounds(workArea)
            Window(
                visible = true,
                state = androidx.compose.ui.window.rememberWindowState(
                    position = androidx.compose.ui.window.WindowPosition(place.x.dp, place.y.dp),
                    size = androidx.compose.ui.unit.DpSize(place.width.dp, place.height.dp),
                ),
                onCloseRequest = { peek.dismiss() },
                undecorated = true,
                transparent = true,
                resizable = false,
                alwaysOnTop = true,
                focusable = false,
                title = "Point — пришло",
            ) {
                com.point.desktop.ui.PointDesktopTheme {
                    com.point.desktop.ui.PeekCard(
                        item = shown,
                        source = peek.sourceOfCurrent() ?: ObjectSource.PHONE_RELAY,
                        onOpen = {
                            peek.take()?.let { arrived ->
                                openRequest.value = arrived.obj.id
                                compactVisible.value = true
                            }
                        },
                        onDismiss = { peek.dismiss() },
                    )
                }
            }
        }
    }
}

/**
 * Знак Point на ПК — тот же, что лончер-иконка телефона (решение владельца:
 * все иконки — портал-кольцо из дизайна): тёмная плашка, светящееся кольцо
 * светлым кверху и синим книзу. Рисуется кодом — фон прозрачен, читается в 16 px.
 */
private fun pointGlyph(badge: Boolean = false): androidx.compose.ui.graphics.painter.Painter =
    object : androidx.compose.ui.graphics.painter.Painter() {
        override val intrinsicSize = androidx.compose.ui.geometry.Size(64f, 64f)
        override fun androidx.compose.ui.graphics.drawscope.DrawScope.onDraw() {
            drawPointMark(badge = badge)
        }
    }

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

    val printer = runCatching { javax.print.PrintServiceLookup.lookupDefaultPrintService() }.getOrNull()
    return if (printer == null) "на компьютере нет принтера" else null
}
