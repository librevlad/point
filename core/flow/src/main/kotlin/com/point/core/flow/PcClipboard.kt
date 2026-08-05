package com.point.core.flow

/**
 * One clipboard payload crossing between phone and PC (#161 «общий буфер»): its [mime], a display
 * [name] (for files), and raw [bytes]. Text is `text/plain` with UTF-8 bytes; a screenshot is
 * `image/png`; a copied file is its own mime. Not a data class — [bytes] equality would be reference
 * based anyway; callers compare via [signature].
 */
class ClipboardPayload(val mime: String, val name: String, val bytes: ByteArray) {
    val isText: Boolean get() = mime.startsWith("text/")
    val isImage: Boolean get() = mime.startsWith("image/")

    /** The text, when this payload is text. */
    fun text(): String = String(bytes, Charsets.UTF_8)

    /** A cheap identity for «did the clipboard change since last sync» (mime + size + content hash). */
    fun signature(): String = "$mime:${bytes.size}:${bytes.contentHashCode()}"

    companion object {
        fun ofText(text: String) = ClipboardPayload("text/plain", "", text.toByteArray(Charsets.UTF_8))
    }
}

/**
 * Shared clipboard with the linked PC (#161 — «общий буфер, как в Apple»). Android forbids a
 * background app from touching the clipboard, so the sync is triggered from a Quick Settings tile: a
 * momentary foreground activity reads/writes the phone clipboard and calls this. The tile [push]es
 * when the phone's clipboard changed since the last sync, otherwise [pull]s the PC's — so a copy on
 * either device (text, image, or file) lands on the other without clipboard-conflict versioning.
 */
interface PcClipboardSync {
    /** Send the phone's clipboard [payload] to the PC's system clipboard. */
    suspend fun push(pc: LinkedPc, payload: ClipboardPayload): ClipPush

    /** The PC's current clipboard. [ClipPull.Empty] and [ClipPull.Unreachable] are kept distinct
     *  because they are different answers: «компьютер ответил, и там пусто» — это ответ. */
    suspend fun pull(pc: LinkedPc): ClipPull
}

/**
 * Почему буфер не синхронизировался — и это не «попробуйте ещё раз» (#272). Одно «Компьютер
 * недоступен» на все случаи прятало настоящие причины: снимок на 60 МБ, который сервер не берёт, и
 * отключённое из круга устройство выглядели одинаково, хотя чинятся по-разному. Инвариант тот же:
 * «не глотай ошибки» — различимые отказы остаются различимыми.
 */
enum class ClipFail {
    /** Больше, чем сервер берёт за раз, — повтор ничего не уменьшит. */
    TOO_BIG,

    /** Сервер не признал это устройство: его отключили из круга. */
    AUTH,
}

/** The outcome of a clipboard [PcClipboardSync.push]. */
sealed interface ClipPush {
    /** Delivered to (or deposited for) the PC. */
    data object Sent : ClipPush

    /** Письмо не легло в ящик: компьютера нет в круге, он не запущен или сервер молчит. */
    data object Unreachable : ClipPush

    /** Отказ, который повтором не чинится ([ClipFail]). */
    data class Failed(val why: ClipFail) : ClipPush
}

/** The outcome of a clipboard [PcClipboardSync.pull]. */
sealed interface ClipPull {
    /** The PC's clipboard, read successfully. */
    data class Got(val payload: ClipboardPayload) : ClipPull

    /** Компьютер ответил, и буфер у него пуст — это ответ, а не молчание. */
    data object Empty : ClipPull

    /** Компьютер письма не забрал: его нет в круге, он не запущен или сервер молчит. */
    data object Unreachable : ClipPull

    /** Отказ, который повтором не чинится ([ClipFail]). */
    data class Failed(val why: ClipFail) : ClipPull
}

/**
 * Что буфер кладёт в мету письма — те же два поля, что у объекта: чем это открыть и как называется.
 *
 * Своего кодека у буфера больше нет (#475). Раньше их было два — один у объектов, другой у буфера,
 * — и держались они врозь тремя выдуманными адресами ящиков. Ящик стал один на устройство, вид
 * письма переехал в [RelayRpc.KIND], и второй кодек оказался лишней правдой об одном и том же.
 */
fun clipMeta(payload: ClipboardPayload): Map<String, String> =
    mapOf(CLIP_MIME to payload.mime, CLIP_NAME to payload.name)

/** Буфер из меты и байтов письма; `null` — содержимого не было (пустой буфер на том конце). */
fun clipPayloadOf(meta: Map<String, String>, bytes: ByteArray): ClipboardPayload? =
    meta[CLIP_MIME]?.takeIf { it.isNotBlank() }
        ?.let { ClipboardPayload(it, meta[CLIP_NAME].orEmpty(), bytes) }

const val CLIP_MIME = "mime"
const val CLIP_NAME = "name"
