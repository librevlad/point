package com.point.desktop

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.point.desktop.ui.DesktopApp
import java.awt.FileDialog
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.dnd.DnDConstants
import java.awt.dnd.DropTarget
import java.awt.dnd.DropTargetAdapter
import java.awt.dnd.DropTargetDropEvent
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

    val registry = DesktopRegistry(
        setOf(PcOpenCapability(), PcCopyCapability(), PcRevealCapability(), PcSaveAsCapability()),
    )
    val resolver = DesktopResolver(
        setOf(
            PcOpenRealizer(opener),
            PcCopyRealizer(clipboard),
            PcRevealRealizer(revealer),
            PcSaveAsRealizer(saveTarget),
        ),
    )
    state = DesktopState(registry, resolver, clipboard)

    val server = PcServer(
        inbox = inbox,
        token = config.token,
        pcName = config.name,
        pairGate = state::askPair,
        onReceived = state::onReceived,
        // #80: advertise the actions the phone may run here. Save-as stays local-only —
        // it opens a target dialog, which nobody expects to pop from a remote tap.
        remoteActions = listOf(
            com.point.core.flow.PcRemoteAction("pc-open", "Открыть на компьютере"),
            com.point.core.flow.PcRemoteAction("pc-copy", "В буфер компьютера"),
            com.point.core.flow.PcRemoteAction("pc-reveal", "Показать в папке на ПК"),
        ),
        runAction = state::runRemoteAction,
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
        ) {
            // Local input: OS drag&drop straight onto the window (AWT DropTarget is the
            // dependable route on every platform; files and plain text both land).
            window.dropTarget = DropTarget(
                window,
                object : DropTargetAdapter() {
                    override fun drop(event: DropTargetDropEvent) {
                        event.acceptDrop(DnDConstants.ACTION_COPY)
                        val t = event.transferable
                        runCatching {
                            when {
                                t.isDataFlavorSupported(DataFlavor.javaFileListFlavor) -> {
                                    @Suppress("UNCHECKED_CAST")
                                    (t.getTransferData(DataFlavor.javaFileListFlavor) as List<File>)
                                        .forEach { state.onReceived(inbox.addFile(it.absolutePath)) }
                                }
                                t.isDataFlavorSupported(DataFlavor.stringFlavor) -> {
                                    val text = t.getTransferData(DataFlavor.stringFlavor) as String
                                    state.onReceived(inbox.addText(text))
                                }
                            }
                        }
                        event.dropComplete(true)
                    }
                },
            )
            DesktopApp(
                state = state,
                config = config,
                addresses = siteLocalAddresses(),
                port = server.port,
            )
        }
    }
}
