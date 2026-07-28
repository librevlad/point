package com.point.desktop

import androidx.compose.ui.res.painterResource
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
fun main() {
    val config = FilePcConfig(File(System.getProperty("user.home"), ".point-pc")).load()
    val inbox = Inbox(File(System.getProperty("user.home"), "Point"))

    val clipboard = TextClipboard { text ->
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
    }
    val opener = SystemOpener { file -> java.awt.Desktop.getDesktop().open(file) }
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
    val registry = DesktopRegistry(
        setOf(PcOpenCapability(), PcCopyCapability(), PcRevealCapability(), PcSaveAsCapability(), PcDownloadCapability(), PcToPhoneCapability()),
    )
    val resolver = DesktopResolver(
        setOf(
            PcOpenRealizer(opener),
            PcCopyRealizer(clipboard),
            PcRevealRealizer(revealer),
            PcSaveAsRealizer(saveTarget),
            PcDownloadRealizer(downloader),
            PcToPhoneRealizer(outbox),
        ),
    )
    val phoneCapsFile = File(File(System.getProperty("user.home"), ".point-pc"), "phone-caps")
    state = DesktopState(
        registry, resolver, clipboard, outbox,
        persistPhoneCaps = { caps ->
            runCatching { phoneCapsFile.apply { parentFile?.mkdirs() }.writeText(com.point.core.flow.encodePcCaps(caps)) }
        },
    )
    runCatching { com.point.core.flow.decodePcCaps(phoneCapsFile.readText()) }
        .getOrNull()?.let(state::setPhoneCaps)

    val server = PcServer(
        inbox = inbox,
        token = config.token,
        pcName = config.name,
        pairGate = state::askPair,
        onReceived = state::onReceived,
        // #80: advertise the actions the phone may run here. Save-as stays local-only —
        // it opens a target dialog, which nobody expects to pop from a remote tap.
        remoteActions = buildList {
            add(com.point.core.flow.PcRemoteAction("pc-open", "Открыть на компьютере"))
            add(com.point.core.flow.PcRemoteAction("pc-copy", "В буфер компьютера"))
            add(com.point.core.flow.PcRemoteAction("pc-reveal", "Показать в папке на ПК"))
            // Advertised only when yt-dlp actually exists on this PC — a bubble that
            // cannot run must never appear on the phone.
            if (downloader.available()) {
                add(com.point.core.flow.PcRemoteAction("pc-download", "Скачать видео на ПК", kinds = setOf("URL")))
            }
        },
        runAction = state::runRemoteAction,
        outbox = outbox,
        onPhoneCaps = state::setPhoneCaps,
    )
    server.start(preferredPort = config.port)
    // Slice C: let phones discover this PC by themselves (best-effort mDNS).
    val advertiser = Advertiser(config.name, server.port).also { it.start() }

    application {
        Window(
            onCloseRequest = {
                advertiser.stop()
                server.stop()
                exitApplication()
            },
            title = "Point для ПК",
            icon = painterResource("point-icon.png"),
        ) {
            // Local input: native Compose drag&drop (the AWT window.dropTarget never fired —
            // the Compose surface intercepts drops; DesktopApp uses Modifier.dragAndDropTarget).
            DesktopApp(
                state = state,
                config = config,
                addresses = siteLocalAddresses(),
                port = server.port,
                onFilesDropped = { files -> files.forEach { state.onReceived(inbox.addFile(it.absolutePath)) } },
                onTextDropped = { text -> state.onReceived(inbox.addText(text)) },
            )
        }
    }
}
