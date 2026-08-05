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
            PcOpenCapability(), PcCopyCapability(), PcRevealCapability(), PcSaveAsCapability(),
            PcDownloadCapability(), PcToPhoneCapability(), PcPrintCapability(),
            PcOfficePdfCapability(),
            // Работа с содержимым, а не с файлом как с файлом (#585).
            PcEntitiesCapability(), PcUnderstandCapability(), PcTranslateCapability(),
            PcAskCapability(), PcQrCapability(), PcDropCapability(),
            PcUnzipCapability(), PcOpenLinkCapability(), PcOfficeTextCapability(),
            PcShrinkImageCapability(), PcTranscribeCapability(),
        ),
    )
    val resolver = DesktopResolver(
        setOf(
            PcOpenRealizer(opener),
            PcCopyRealizer(clipboard),
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
    runCatching { com.point.core.flow.decodePcCaps(phoneCapsFile.readText()) }
        .getOrNull()?.let(state::setPhoneCaps)

    // Что этот компьютер умеет. Канал один (#475), и список у него тоже один: второй правды о
    // возможностях ПК в проекте не заводится.
    val pcRemoteActions = buildList {
            add(com.point.core.flow.PcRemoteAction("pc-open", "Открыть на компьютере"))
            add(com.point.core.flow.PcRemoteAction("pc-copy", "В буфер компьютера"))
            add(com.point.core.flow.PcRemoteAction("pc-reveal", "Показать в папке на ПК"))
            add(
                com.point.core.flow.PcRemoteAction(
                    "pc-download", "Скачать видео на ПК", kinds = setOf("URL"),
                    unavailable = if (downloader.available()) null else "на компьютере нет yt-dlp",
                ),
            )
            // #291: печать отрабатывает, только если система её поддерживает и принтер по
            // умолчанию есть; #316: если нет — говорим, чего именно не хватает.
            add(com.point.core.flow.PcRemoteAction("pc-print", "Напечатать на ПК", unavailable = whyCannotPrint()))
            // Телефон рисовать слайды не умеет, компьютер умеет — и говорит об этом прямо. Нет
            // конвертера — действие приезжает недоступным, кнопки на телефоне не будет (#316).
            add(
                com.point.core.flow.PcRemoteAction(
                    "pc-office-pdf",
                    "Сделать PDF на ПК",
                    kinds = setOf("OFFICE"),
                    unavailable = officeToPdf.whyUnavailable(),
                ),
            )
            // Работа с содержимым (#585). Она нужна и на телефоне, и на компьютере — но телефон
            // тянет её к себе только тогда, когда своего пути нет: например, ключ AI вписан на
            // компьютере, а на телефоне его нет.
            add(com.point.core.flow.PcRemoteAction("pc-entities", "Найти в тексте на ПК", kinds = setOf("TEXT")))
            add(
                com.point.core.flow.PcRemoteAction(
                    "pc-qr", "Сделать QR на ПК", kinds = setOf("TEXT", "URL"),
                ),
            )
            // Три AI-действия приезжают недоступными, пока ключа на компьютере нет: кнопки на
            // телефоне не будет вовсе, и человек не потратит тап на молчание (#316).
            val noKey = if (FilePcConfig(pointDir).load().ai.key.isNotBlank()) {
                null
            } else {
                "на компьютере не задан ключ AI"
            }
            add(com.point.core.flow.PcRemoteAction("pc-understand", "Понять на ПК", kinds = setOf("TEXT"), unavailable = noKey))
            add(com.point.core.flow.PcRemoteAction("pc-translate", "Перевести на ПК", kinds = setOf("TEXT"), unavailable = noKey))
            add(com.point.core.flow.PcRemoteAction("pc-ask", "Спросить AI на ПК", kinds = setOf("TEXT"), unavailable = noKey))
            // Ссылку выдаёт сервер, значит нужен вход. Не вошли — действие приезжает недоступным.
            add(
                com.point.core.flow.PcRemoteAction(
                    "pc-drop", "Дать ссылку с ПК",
                    unavailable = if (accountStore.current() != null) null else "компьютер не вошёл в аккаунт",
                ),
            )
            add(com.point.core.flow.PcRemoteAction("pc-unzip", "Распаковать на ПК", kinds = setOf("ZIP")))
            add(com.point.core.flow.PcRemoteAction("pc-office-text", "Достать текст на ПК", kinds = setOf("OFFICE")))
            add(com.point.core.flow.PcRemoteAction("pc-shrink", "Сделать легче на ПК", kinds = setOf("IMAGE")))
            add(
                com.point.core.flow.PcRemoteAction(
                    "pc-transcribe", "Расшифровать на ПК", kinds = setOf("AUDIO"),
                    unavailable = if (FilePcConfig(pointDir).load().speech.key.isNotBlank()) {
                        null
                    } else {
                        "на компьютере не задан ключ расшифровки"
                    },
                ),
            )
            add(com.point.core.flow.PcRemoteAction("pc-open-link", "Открыть в браузере на ПК", kinds = setOf("URL")))
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
