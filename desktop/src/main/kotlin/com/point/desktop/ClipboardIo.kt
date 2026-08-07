package com.point.desktop

import com.point.core.flow.ClipboardPayload
import java.awt.Image
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO

fun readSystemClipboard(): ClipboardPayload? = runCatching {
    val cb = Toolkit.getDefaultToolkit().systemClipboard
    when {
        cb.isDataFlavorAvailable(DataFlavor.javaFileListFlavor) -> {
            @Suppress("UNCHECKED_CAST")
            val files = cb.getData(DataFlavor.javaFileListFlavor) as? List<File>
            files?.firstOrNull()?.takeIf { it.isFile }
                ?.let { ClipboardPayload(mimeFor(it.name), it.name, it.readBytes()) }
        }
        cb.isDataFlavorAvailable(DataFlavor.imageFlavor) -> {
            val image = cb.getData(DataFlavor.imageFlavor) as Image
            val out = ByteArrayOutputStream()
            ImageIO.write(toBufferedImage(image), "png", out)
            ClipboardPayload("image/png", "clipboard.png", out.toByteArray())
        }
        cb.isDataFlavorAvailable(DataFlavor.stringFlavor) ->
            ClipboardPayload.ofText(cb.getData(DataFlavor.stringFlavor) as String)
        else -> null
    }
}.getOrNull()

fun writeSystemClipboard(payload: ClipboardPayload) {
    runCatching {
        val cb = Toolkit.getDefaultToolkit().systemClipboard
        when {
            payload.isText -> cb.setContents(StringSelection(payload.text()), null)
            payload.isImage -> ImageIO.read(ByteArrayInputStream(payload.bytes))
                ?.let { cb.setContents(ImageTransferable(it), null) }
            else -> {
                val dir = File(System.getProperty("java.io.tmpdir"), "point-clip").apply { mkdirs() }
                val file = File(dir, payload.name.ifBlank { "clip.bin" }).apply { writeBytes(payload.bytes) }
                cb.setContents(FileListTransferable(listOf(file)), null)
            }
        }
    }
}

private fun toBufferedImage(image: Image): BufferedImage {
    if (image is BufferedImage) return image
    val buffered = BufferedImage(
        image.getWidth(null).coerceAtLeast(1),
        image.getHeight(null).coerceAtLeast(1),
        BufferedImage.TYPE_INT_ARGB,
    )
    buffered.createGraphics().apply { drawImage(image, 0, 0, null); dispose() }
    return buffered
}

private class ImageTransferable(private val image: BufferedImage) : Transferable {
    override fun getTransferDataFlavors() = arrayOf(DataFlavor.imageFlavor)
    override fun isDataFlavorSupported(flavor: DataFlavor) = flavor == DataFlavor.imageFlavor
    override fun getTransferData(flavor: DataFlavor): Any =
        if (isDataFlavorSupported(flavor)) image else throw UnsupportedFlavorException(flavor)
}

private class FileListTransferable(private val files: List<File>) : Transferable {
    override fun getTransferDataFlavors() = arrayOf(DataFlavor.javaFileListFlavor)
    override fun isDataFlavorSupported(flavor: DataFlavor) = flavor == DataFlavor.javaFileListFlavor
    override fun getTransferData(flavor: DataFlavor): Any =
        if (isDataFlavorSupported(flavor)) files else throw UnsupportedFlavorException(flavor)
}
