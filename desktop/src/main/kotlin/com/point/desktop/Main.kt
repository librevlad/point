package com.point.desktop

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import java.awt.FileDialog
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File

private const val SCREEN_GRAB_DELAY_MS = 220L

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
    val resolver = DesktopResolver(
        setOf(
            PcOpenRealizer(opener),
            PcCopyRealizer(clipboard, imageClipboard = ::writeSystemClipboard),
            PcRevealRealizer(revealer),
            PcSaveAsRealizer(saveTarget),
            PcDownloadRealizer(downloader),
            PcToPhoneRealizer(outbox),
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
            PcOfficeTextRealizer(com.point.core.flow.OoxmlOfficeTextExtractor(), outbox),
            PcShrinkImageRealizer(outbox),
            PcTranscribeRealizer({ FilePcConfig(pointDir).load().speech }, outbox),
            PcCloudOcrRealizer({ FilePcConfig(pointDir).load().ocr }, entities),
            PcOpenLinkRealizer { url ->
                runCatching { java.awt.Desktop.getDesktop().browse(java.net.URI(url)) }
            },
        ),
    )
    val registry = DesktopRegistry(
        setOf(

            PcOpenCapability(), PcCopyCapability(), PcRevealCapability(), PcSaveAsCapability(),
            PcDownloadCapability(), PcToPhoneCapability(), PcPrintCapability(),
            PcOpenLinkCapability(),

            // «Понять»/«Перевести»/«Спросить AI» на компьютере убраны (#701, решение
            // владельца «Убрать, ПК — только исполнитель»): результат для человека тот
            // же, что и на телефоне, — компьютер не должен быть отдельной дверью к
            // тому же самому. Остаются действия, привязанные к месту исполнения.
            PcTranscribeCapability(),

            PcEntitiesCapability(),
        ) +

            com.point.core.flow.capabilities.sharedCapabilities(),

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

        browser = { url -> runCatching { java.awt.Desktop.getDesktop().browse(java.net.URI(url)) } },
        deviceName = config.name,
        keys = deviceKeys,
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

        reopenPath = { path -> File(path).takeIf(File::isFile)?.let { inbox.addFile(it.absolutePath) } },
        consent = FileConsent(File(pointDir, "consent")),

        // «Скинули с телефона — высветилась часть окна»: своя плашка, не системное
        // уведомление. Готовое здесь (PDF, скачанное) объявляется тем же путём.
        announce = { item, source ->
            peek.arrived(item, compactVisible.value, source)

            // Звучит только прилёт с телефона (#650): пара к свипу ухода на той стороне.
            // Своё, здешнее и принесённое мышью, звука не просит — оно и так на глазах.
            if (source == ObjectSource.PHONE_RELAY) portalSound.arrived()
        },
    )

    val shellMenu = RegistryShellMenu()
    runCatching {
        val exe = installedExecutable(ProcessHandle.current().info().command().orElse(null))
        if (exe != null && FilePcConfig(pointDir).load().rightClick) {
            val wanted = shellCommandFor(exe)
            if (shellMenuNeedsUpdate(shellMenu.registeredCommand(), wanted)) {
                shellMenu.register(wanted, "Открыть в Point")
            }
        }
    }

    runCatching { inbox.sweep(System.currentTimeMillis() - 24L * 60 * 60 * 1000) }

    runCatching { com.point.core.flow.decodePcCaps(phoneCapsFile.readText()) }
        .getOrNull()?.let { state.setPhoneCaps(it, persist = false) }

    val pcSuffix = mapOf(
        "pc-open" to "Открыть на компьютере",
        "pc-copy" to "В буфер компьютера",
        "pc-reveal" to "Показать в папке на ПК",
    )
    val pcUnavailable: Map<String, String?> = mapOf(
        "pc-download" to if (downloader.available()) null else "на компьютере нет yt-dlp",

        "pc-print" to whyCannotPrint(),
        "pdf" to officeToPdf.whyUnavailable(),

        "pc-transcribe" to speechKeyMissing(pointDir),

        "drop-link" to if (accountStore.current() != null) null else "компьютер не вошёл в аккаунт",
    )
    val pcRemoteActions = com.point.core.flow.advertisedActions(registry.all()).map { action ->
        action.copy(

            label = pcSuffix[action.id] ?: (action.label + " на ПК"),
            unavailable = pcUnavailable[action.id],

            leavesCircle = resolver.leavesDevice(com.point.core.model.CapabilityId(action.id)),
        )
    }

    filesFromArgs(args).forEach { file ->
        state.onReceived(inbox.addFile(file.absolutePath), ObjectSource.LOCAL)
    }

    val requests = RelayRequests(
        remoteActions = { pcRemoteActions },
        outbox = outbox,
        onPhoneCaps = state::setPhoneCaps,

        onSecrets = { theirs -> FilePcConfig(pointDir).mergeSecrets(theirs) },
        clipboardGet = ::readSystemClipboard,
        clipboardSet = ::writeSystemClipboard,
        onObject = { name, mime, meta, bytes, action ->
            val item = inbox.receive(name, mime, meta, bytes.inputStream())
            state.onReceived(item, ObjectSource.PHONE_RELAY)
            action?.let { state.runRemoteActionNow(it, item) }
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
            runCatching {
                SendToRunning.collectHandOffs(pointDir).forEach { file ->
                    state.onReceived(inbox.addFile(file.absolutePath), ObjectSource.LOCAL)

                    // «Открыть в Point» — человек сам позвал: окошко выходит само.
                    compactVisible.value = true
                }

                // Вторая копия не живёт — она будит эту и уходит.
                if (SendToRunning.takeWake(pointDir)) compactVisible.value = true
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
            alwaysOnTop = true,
            title = "Point",
            icon = androidx.compose.runtime.remember { pointGlyph() },
        ) {
            com.point.desktop.ui.PointDesktopTheme {
                com.point.desktop.ui.CompactApp(
                    state = state,
                    config = config,
                    account = account,
                    openObject = openRequest,
                    onObjectOpened = { openRequest.value = null },
                    onFilesDropped = { files ->
                        files.forEach { state.onReceived(inbox.addFile(it.absolutePath), ObjectSource.DROPPED) }
                    },
                    onTextDropped = { text -> state.onReceived(inbox.addText(text), ObjectSource.DROPPED) },
                    onClipboardTaken = { text -> state.onReceived(inbox.addText(text), ObjectSource.CLIPBOARD) },
                    onWipe = { inbox.wipe() },
                    onSaveSettings = { changed ->
                        runCatching { FilePcConfig(pointDir).save(changed) }

                        runCatching {
                            val exe = installedExecutable(ProcessHandle.current().info().command().orElse(null))
                            when {
                                !changed.rightClick -> shellMenu.unregister()
                                exe != null -> shellMenu.register(shellCommandFor(exe), "Открыть в Point")
                            }
                        }
                    },
                    onSweepNow = {
                        runCatching { inbox.sweep(System.currentTimeMillis() - 24L * 60 * 60 * 1000) }
                    },
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
            val c = center
            val r = size.minDimension / 2f

            // Тёмная плашка — как поле мобильной иконки.
            drawCircle(
                brush = androidx.compose.ui.graphics.Brush.radialGradient(
                    0f to androidx.compose.ui.graphics.Color(0xFF141021),
                    1f to androidx.compose.ui.graphics.Color(0xFF08080E),
                    center = c, radius = r,
                ),
                radius = r * 0.94f,
                center = c,
            )

            // Мягкий ореол кольца.
            drawCircle(
                color = androidx.compose.ui.graphics.Color(0xFF7B5CFF).copy(alpha = 0.30f),
                radius = r * 0.60f,
                center = c,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = r * 0.36f),
            )

            // Само кольцо: светлое кверху, фиолетовое, синее книзу — как на телефоне.
            drawCircle(
                brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                    0f to androidx.compose.ui.graphics.Color(0xFFEAF0FF),
                    0.45f to androidx.compose.ui.graphics.Color(0xFF9B7BFF),
                    1f to androidx.compose.ui.graphics.Color(0xFF00A6FF),
                ),
                radius = r * 0.56f,
                center = c,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = r * 0.30f),
            )
            if (badge) {
                drawCircle(
                    color = androidx.compose.ui.graphics.Color(0xFF00E0FF),
                    radius = r * 0.20f,
                    center = androidx.compose.ui.geometry.Offset(size.width * 0.82f, size.height * 0.18f),
                )
            }
        }
    }

private fun aiKeyMissing(pointDir: java.io.File): String? =
    if (FilePcConfig(pointDir).load().ai.key.isNotBlank()) null else "на компьютере не задан ключ AI"

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
