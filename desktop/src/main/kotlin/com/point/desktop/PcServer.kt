package com.point.desktop

import com.point.core.flow.PcActionOutcome
import com.point.core.flow.PcRemoteAction
import com.point.core.flow.decodePcMeta
import com.point.core.flow.decodePcCaps
import com.point.core.flow.encodePcCaps
import com.point.core.flow.encodePcOutbox
import com.point.core.flow.encodePcReceiveReply
import java.io.File
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.Executors

/**
 * The LAN receiver (#147) on the JDK's own HttpServer — zero dependencies.
 *
 * - `POST /pair`   — asks the user via [pairGate] (blocks up to its timeout); 200 = token.
 * - `POST /receive`— constant-time token check, base64 headers (ASCII-safe Cyrillic),
 *                    body streamed into the inbox; 401 on a bad token.
 * - `GET  /ping`   — "point-pc <name>", the connectivity probe for manual pairing.
 * - `GET  /caps`   — the PC's remote actions (`id=label` lines) for the paired phone (#80).
 * - `GET  /outbox`, `GET /outbox/file` (X-Point-Id), `POST /outbox/ack` — the pull
 *   queue of «На телефон» objects (#161); same token gate everywhere.
 *
 * A cached thread pool is essential: the default executor runs handlers on the accept
 * thread, and a pending pair dialog would freeze every other request.
 */
class PcServer(
    private val inbox: Inbox,
    private val token: String,
    private val pcName: String,
    private val pairGate: (deviceName: String) -> Boolean,
    /** Объект принят; вторым аргументом — откуда он пришёл, для журнала пути (#407). */
    private val onReceived: (InboxItem, ObjectSource) -> Unit,
    /** Телефон дал о себе знать по локальной сети (#412): экран должен это показать. */
    private val onContact: () -> Unit = {},
    private val remoteActions: List<PcRemoteAction> = emptyList(),
    /**
     * Выполнить заказанное телефоном действие и **вернуть его исход** (#114).
     *
     * `null` — «неизвестно»: действия не заказывали или мы не дождались. Раньше здесь стоял
     * `-> Unit`, исход выбрасывался, и телефон переводил «файл доехал» в «готово».
     */
    private val runAction: (id: String, item: InboxItem) -> PcActionOutcome? = { _, _ -> null },
    private val outbox: Outbox? = null,
    private val onPhoneCaps: (List<PcRemoteAction>) -> Unit = {},
    // #161 «общий буфер»: read/write the PC's system clipboard for the shared-clipboard tile.
    private val clipboardGet: () -> com.point.core.flow.ClipboardPayload? = { null },
    private val clipboardSet: (com.point.core.flow.ClipboardPayload) -> Unit = {},
) {
    private var server: HttpServer? = null
    val port: Int get() = server?.address?.port ?: -1

    fun start(preferredPort: Int) {
        val s = bind(preferredPort)
        s.executor = Executors.newCachedThreadPool { r -> Thread(r, "point-pc-http").apply { isDaemon = true } }
        s.createContext("/ping") { ex ->
            respond(ex, 200, "point-pc $pcName")
        }
        s.createContext("/pair") { ex ->
            val device = ex.requestHeaders.getFirst("X-Point-Name")?.let(::unb64) ?: "телефон"
            ex.requestBody.readBytes() // drain
            if (pairGate(device)) respond(ex, 200, token) else respond(ex, 403, "denied")
        }
        s.createContext("/caps") { ex ->
            val given = ex.requestHeaders.getFirst("X-Point-Token").orEmpty()
            if (!MessageDigest.isEqual(given.toByteArray(), token.toByteArray())) {
                respond(ex, 401, "bad token")
            } else {
                respond(ex, 200, encodePcCaps(remoteActions))
            }
        }
        s.createContext("/phone-caps") { ex ->
            withToken(ex) {
                val caps = decodePcCaps(String(ex.requestBody.readBytes()))
                onPhoneCaps(caps)
                respond(ex, 200, "ok")
            }
        }
        s.createContext("/outbox") { ex ->
            withToken(ex) { respond(ex, 200, encodePcOutbox(outbox?.entries().orEmpty())) }
        }
        s.createContext("/outbox/file") { ex ->
            withToken(ex) {
                val id = ex.requestHeaders.getFirst("X-Point-Id")?.trim()?.toIntOrNull()
                val file = id?.let { outbox?.file(it) }
                val mime = id?.let { i -> outbox?.entries()?.firstOrNull { it.id == i }?.meta?.get("mime") }
                if (file == null) respond(ex, 404, "no such entry") else respondFile(ex, file, mime ?: "application/octet-stream")
            }
        }
        s.createContext("/outbox/ack") { ex ->
            withToken(ex) {
                ex.requestBody.readBytes()
                ex.requestHeaders.getFirst("X-Point-Id")?.trim()?.toIntOrNull()?.let { outbox?.remove(it) }
                respond(ex, 200, "ok")
            }
        }
        // #161 «общий буфер»: GET returns the PC's clipboard (mime+name headers, bytes body); POST
        // sets it from the phone's. Text/image/file all cross as raw bytes.
        s.createContext("/clipboard") { ex ->
            withToken(ex) {
                if (ex.requestMethod == "POST") {
                    val mime = ex.requestHeaders.getFirst("X-Clip-Mime") ?: "text/plain"
                    val name = ex.requestHeaders.getFirst("X-Clip-Name")?.let(::unb64).orEmpty()
                    clipboardSet(com.point.core.flow.ClipboardPayload(mime, name, ex.requestBody.readBytes()))
                    respond(ex, 200, "ok")
                } else {
                    val p = clipboardGet()
                    if (p == null) {
                        ex.sendResponseHeaders(200, 0); ex.responseBody.close()
                    } else {
                        ex.responseHeaders.add("X-Clip-Mime", p.mime)
                        ex.responseHeaders.add(
                            "X-Clip-Name",
                            java.util.Base64.getEncoder().encodeToString(p.name.toByteArray(Charsets.UTF_8)),
                        )
                        ex.sendResponseHeaders(200, p.bytes.size.toLong())
                        ex.responseBody.use { it.write(p.bytes) }
                    }
                }
            }
        }
        s.createContext("/receive") { ex ->
            val given = ex.requestHeaders.getFirst("X-Point-Token").orEmpty()
            if (!MessageDigest.isEqual(given.toByteArray(), token.toByteArray())) {
                ex.requestBody.readBytes()
                respond(ex, 401, "bad token")
                return@createContext
            }
            val mime = ex.requestHeaders.getFirst("X-Point-Mime") ?: "application/octet-stream"
            val name = ex.requestHeaders.getFirst("X-Point-Name")?.let(::unb64)
                ?.takeIf { it.isNotBlank() } ?: "объект.${extFor(mime)}"
            val meta = ex.requestHeaders.getFirst("X-Point-Meta")?.let { decodePcMeta(unb64(it)) }.orEmpty()
            val item = inbox.receive(name, mime, meta, ex.requestBody)
            // Откуда объект пришёл, видно по адресу (#407): через этот же `/receive` стучится
            // «Отправить в Point» из проводника уже работающего компьютера (#252). Записать такой
            // файл как приехавший с телефона значило бы врать человеку в его же истории.
            val local = runCatching { ex.remoteAddress.address.isLoopbackAddress }.getOrDefault(false)
            onReceived(item, if (local) ObjectSource.LOCAL else ObjectSource.PHONE_LAN)
            // #80: the phone may name one of the advertised actions to run right away.
            // Unknown or failing actions never fail the receive — the object landed.
            // #316: недоступное действие не запускается, даже если его назвали, — старый
            // телефон его не увидит, а телефон с протухшим кэшем не должен продавить печать
            // на компьютере, где принтера уже нет. Объект при этом всё равно доехал.
            //
            // #114: приём по-прежнему не падает из-за действия — но его исход больше не
            // выбрасывается. Он едет в ответе, и телефон говорит человеку то, что было на самом
            // деле. Недоступное действие — это отказ с причиной, а не тишина под словом «готово».
            val outcome = ex.requestHeaders.getFirst("X-Point-Action")?.let(::unb64)?.let { actionId ->
                val declared = remoteActions.firstOrNull { it.id == actionId }
                when {
                    declared == null -> PcActionOutcome.Failed("компьютер не знает такого действия")
                    declared.unavailable != null -> PcActionOutcome.Failed(
                        declared.unavailable!!.ifBlank { "компьютер сейчас не может это сделать" },
                    )
                    else -> runCatching { runAction(actionId, item) }
                        .getOrElse { PcActionOutcome.Failed(it.message ?: "не получилось") }
                }
            }
            respond(ex, 200, encodePcReceiveReply(outcome))
        }
        s.start()
        server = s
    }

    private fun bind(preferredPort: Int): HttpServer {
        if (preferredPort > 0) {
            for (candidate in preferredPort..preferredPort + 8) {
                runCatching { return HttpServer.create(InetSocketAddress(candidate), 0) }
            }
        }
        return HttpServer.create(InetSocketAddress(preferredPort.coerceAtLeast(0)), 0)
    }

    fun stop() {
        server?.stop(0)
        server = null
    }

    private fun respond(ex: HttpExchange, code: Int, body: String) {
        val bytes = body.toByteArray()
        ex.responseHeaders.add("Content-Type", "text/plain; charset=utf-8")
        ex.sendResponseHeaders(code, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    /** The shared constant-time token gate — 401 and done unless the caller is paired. */
    private inline fun withToken(ex: HttpExchange, body: () -> Unit) {
        val given = ex.requestHeaders.getFirst("X-Point-Token").orEmpty()
        if (!MessageDigest.isEqual(given.toByteArray(), token.toByteArray())) {
            runCatching { ex.requestBody.readBytes() }
            respond(ex, 401, "bad token")
        } else {
            // Верный токен — это и есть «телефон на связи»: чужой сюда не дойдёт. Отмечается до
            // работы, чтобы даже упавший запрос считался контактом — связь-то была.
            onContact()
            body()
        }
    }

    /** Raw bytes with the right length; an empty file must send -1 (no body) per the JDK contract. */
    private fun respondFile(ex: HttpExchange, file: File, mime: String) {
        ex.responseHeaders.add("Content-Type", mime)
        ex.sendResponseHeaders(200, if (file.length() == 0L) -1 else file.length())
        ex.responseBody.use { out -> file.inputStream().use { it.copyTo(out) } }
    }

    private fun unb64(s: String): String =
        runCatching { String(Base64.getDecoder().decode(s)) }.getOrDefault("")
}
