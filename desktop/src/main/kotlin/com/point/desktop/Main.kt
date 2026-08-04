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
fun main(args: Array<String>) {
    // «Отправить в Point» из проводника (#252). Point на компьютере обычно уже открыт, поэтому
    // файл сначала предлагается живому экземпляру — второе окно на каждый пункт меню человеку не
    // нужно, да и порт у сервера уже занят. Никто не ответил — открываемся сами с этим файлом.
    val handed = runCatching {
        SendToRunning.handOff(filesFromArgs(args), FilePcConfig(File(System.getProperty("user.home"), ".point-pc")).load())
    }.getOrDefault(false)
    if (handed) return
    val config = FilePcConfig(File(System.getProperty("user.home"), ".point-pc")).load()
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

    val downloader = YtDlpDownloader(File(System.getProperty("user.home"), "Point/downloads"))
    val outbox = Outbox(File(System.getProperty("user.home"), "Point/outbox"))
    // Чем компьютер умеет рисовать слайды (#403). Ищется один раз при старте: между запусками
    // Office не появляется, а дёргать файловую систему на каждый тап незачем.
    val officeToPdf = LocalOfficeToPdf()
    val registry = DesktopRegistry(
        setOf(
            PcOpenCapability(), PcCopyCapability(), PcRevealCapability(), PcSaveAsCapability(),
            PcDownloadCapability(), PcToPhoneCapability(), PcPrintCapability(),
            PcOfficePdfCapability(),
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
        ),
    )
    val phoneCapsFile = File(File(System.getProperty("user.home"), ".point-pc"), "phone-caps")
    // Память о пути объектов (#407) — рядом с остальным состоянием ПК, тем же способом хранения.
    val journalStore = FileJournalStore(File(File(System.getProperty("user.home"), ".point-pc"), "journal"))
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

    // Что этот компьютер умеет — один список на оба канала: и для локальной сети, и для
    // релея (#161). Второй правды о возможностях ПК в проекте не заводится.
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
        }

    val server = PcServer(
        inbox = inbox,
        token = config.token,
        pcName = config.name,
        pairGate = state::askPair,
        onReceived = state::onReceived,
        onContact = { state.heard(com.point.core.flow.LinkPath.LAN) },
        // #80: advertise the actions the phone may run here. Save-as stays local-only —
        // it opens a target dialog, which nobody expects to pop from a remote tap.
        //
        // #316: то, что этот компьютер умеет, но сейчас не может, объявляется с причиной
        // (`unavailable`), а не молчанием. Кнопкой оно не станет — станет строкой «Почти
        // доступно · нет принтера». Молчание человек читал как «Point не умеет печатать».
        remoteActions = pcRemoteActions,
        // #114: телефон ждёт исход, а не факт доставки — по домашней сети ответить ему есть чем.
        runAction = state::runRemoteActionNow,
        outbox = outbox,
        onPhoneCaps = state::setPhoneCaps,
        clipboardGet = ::readSystemClipboard,
        clipboardSet = ::writeSystemClipboard,
    )
    // Открылись сами: файл из меню становится объектом сразу, без лишнего действия человека.
    filesFromArgs(args).forEach { file ->
        state.onReceived(inbox.addFile(file.absolutePath), ObjectSource.LOCAL)
    }
    server.start(preferredPort = config.port)
    // Slice C: let phones discover this PC by themselves (best-effort mDNS).
    val advertiser = Advertiser(config.name, server.port).also { it.start() }
    // #161 v2 (P4): also receive over the always-works relay — the PC polls the mailbox and an
    // object the phone sent off-LAN lands in the SAME inbox flow as a LAN /receive.
    val relayPoller = RelayPoller(
        relayUrl = RelayEnv.URL,
        appSecret = RelayEnv.APP_SECRET,
        token = config.token,
        onObject = { name, mime, meta, bytes, action ->
            val item = inbox.receive(name, mime, meta, bytes.inputStream())
            state.onReceived(item, ObjectSource.PHONE_RELAY)
            action?.let { runCatching { state.runRemoteAction(it, item) } }
        },
    ).also { it.start() }
    // #161 «общий буфер» через релей: the shared clipboard also works off-LAN — the PC applies a
    // phone push and answers a phone pull over the same blind relay, on its own daemon.
    val relayClipPoller = RelayClipPoller(
        relayUrl = RelayEnv.URL,
        appSecret = RelayEnv.APP_SECRET,
        token = config.token,
        clipboardGet = ::readSystemClipboard,
        clipboardSet = ::writeSystemClipboard,
    ).also { it.start() }

    // #161: компьютер отвечает телефону через релей — что умеет, что приготовил, отдай сделанное.
    // Без этого через релей ходило одно направление, и вне общей сети телефон не мог ни узнать про
    // принтер и сборку PDF, ни забрать результат.
    val relayRequestPoller = RelayRequestPoller(
        relayUrl = RelayEnv.URL,
        appSecret = RelayEnv.APP_SECRET,
        token = config.token,
        remoteActions = { pcRemoteActions },
        outbox = outbox,
        onPhoneCaps = state::setPhoneCaps,
        onContact = { state.heard(com.point.core.flow.LinkPath.RELAY) },
        runAction = state::runRemoteAction,
        log = { line -> println("[relay-rpc] " + line) },
    ).also { it.start() }

    application {
        // Окно мокапа — 1440x900 (#285). Берём чуть меньше, чтобы влезало и на ноутбучный экран,
        // но так, чтобы конвейер помещался целиком: на 800x600 по умолчанию он не помещался.
        val windowState = androidx.compose.ui.window.rememberWindowState(
            width = 1320.dp, height = 900.dp,
        )
        Window(
            state = windowState,
            onCloseRequest = {
                relayClipPoller.stop()
                relayRequestPoller.stop()
                relayPoller.stop()
                advertiser.stop()
                server.stop()
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
                addresses = siteLocalAddresses(),
                port = server.port,
                // Происхождение объекта разделено (#407): перетащенный мышью и взятый из буфера —
                // разные ответы на вопрос «откуда это здесь взялось», и журнал обязан их различать.
                onFilesDropped = { files ->
                    files.forEach { state.onReceived(inbox.addFile(it.absolutePath), ObjectSource.DROPPED) }
                },
                onTextDropped = { text -> state.onReceived(inbox.addText(text), ObjectSource.DROPPED) },
                onClipboardTaken = { text -> state.onReceived(inbox.addText(text), ObjectSource.CLIPBOARD) },
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
