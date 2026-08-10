package com.point.desktop

import java.awt.Image
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.image.BufferedImage
import java.io.File

/**
 * Принесённое в окно Point мышью: что именно принесли и чем на это ответить.
 *
 * Молчаливого отказа быть не может — если объект не родился, человек слышит
 * причину и то, что с ней делать (#546, P7/P9).
 */
sealed interface Dropped {

    /** Файлы: берутся все — из принесённого ничего не пропадает. */
    data class Files(val files: List<File>) : Dropped

    data class Text(val text: String) : Dropped

    /** Картинка прямо со страницы: файла за ней нет — есть пиксели. */
    data class Picture(val image: BufferedImage) : Dropped

    /** Не взяли — с причиной и следующим шагом. */
    data class NotTaken(val why: String) : Dropped
}

const val DROP_UNREADABLE =
    "Не удалось прочитать брошенное — сохраните это на диск и принесите файлом"

const val DROP_EMPTY = "В брошенном ничего не оказалось — попробуйте ещё раз"

const val DROP_ALIEN =
    "Это не файл, не картинка и не текст — сохраните на диск и бросьте сюда файлом"

/**
 * Порядок разбора — от целого к частному: файл, потом пиксели, потом текст.
 * Картинку со страницы браузер отдаёт вместе со ссылкой на неё; человек принёс
 * изображение — Point берёт изображение, а не адрес.
 */
fun readDropped(brought: Transferable): Dropped {
    val supports = { flavor: DataFlavor ->
        runCatching { brought.isDataFlavorSupported(flavor) }.getOrDefault(false)
    }

    if (supports(DataFlavor.javaFileListFlavor)) {
        val files = runCatching {
            (brought.getTransferData(DataFlavor.javaFileListFlavor) as? List<*>)
                .orEmpty().filterIsInstance<File>()
        }.getOrElse { return Dropped.NotTaken(DROP_UNREADABLE) }
        return if (files.isEmpty()) Dropped.NotTaken(DROP_EMPTY) else Dropped.Files(files)
    }

    if (supports(DataFlavor.imageFlavor)) {
        val picture = runCatching { brought.getTransferData(DataFlavor.imageFlavor) as? Image }
            .getOrElse { return Dropped.NotTaken(DROP_UNREADABLE) }
            ?.let(::asBufferedImage)
        return if (picture == null) Dropped.NotTaken(DROP_UNREADABLE) else Dropped.Picture(picture)
    }

    if (supports(DataFlavor.stringFlavor)) {
        val text = runCatching { brought.getTransferData(DataFlavor.stringFlavor) as? String }
            .getOrElse { return Dropped.NotTaken(DROP_UNREADABLE) }
        return if (text.isNullOrBlank()) Dropped.NotTaken(DROP_EMPTY) else Dropped.Text(text)
    }

    return Dropped.NotTaken(DROP_ALIEN)
}

/** Пиксели приходят не всегда готовым снимком — доводим до того, что ляжет на диск. */
fun asBufferedImage(image: Image): BufferedImage? {
    if (image is BufferedImage) return image

    // ImageIcon дожидается загрузки: недогруженная картинка рисуется пустотой.
    val loaded = runCatching { javax.swing.ImageIcon(image).image }.getOrNull() ?: return null
    val width = loaded.getWidth(null)
    val height = loaded.getHeight(null)
    if (width <= 0 || height <= 0) return null
    return runCatching {
        BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB).also { ready ->
            val canvas = ready.createGraphics()
            canvas.drawImage(loaded, 0, 0, null)
            canvas.dispose()
        }
    }.getOrNull()
}

/**
 * Судьба брошенного решается в одном месте, и человек всегда что-то получает:
 * объекты, текст, картинку — или слова о том, почему не вышло.
 *
 * Возвращает то же, чего ждёт системное перетаскивание: взято или нет.
 */
fun takeDropped(
    brought: Dropped,
    files: (List<File>) -> Unit,
    text: (String) -> Unit,
    picture: (BufferedImage) -> Unit,
    say: (String) -> Unit,
): Boolean = when (brought) {
    is Dropped.Files -> {
        files(brought.files)
        filesTakenMessage(brought.files.size)?.let(say)
        true
    }

    is Dropped.Text -> {
        text(brought.text)
        true
    }

    is Dropped.Picture -> {
        picture(brought.image)
        true
    }

    is Dropped.NotTaken -> {
        say(brought.why)
        false
    }
}

/**
 * Несколько файлов разом: берутся все — принесённое не теряется, — но пачку Point
 * не открывает за человека. Он видит её списком и выбирает сам, поэтому про пачку
 * говорится вслух; один файл открывается сам и в отдельных словах не нуждается.
 */
fun filesTakenMessage(count: Int): String? =
    if (count <= 1) null else "Взял $count ${filesWord(count)} — они в списке"

/** Пачка остаётся списком: за человека Point ни один из принесённых не открывает. */
fun droppedAsBatch(brought: Dropped): Boolean =
    brought is Dropped.Files && brought.files.size > 1

private fun filesWord(count: Int): String = when {
    count % 100 in 11..14 -> "файлов"
    count % 10 == 1 -> "файл"
    count % 10 in 2..4 -> "файла"
    else -> "файлов"
}
