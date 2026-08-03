package com.point.desktop

import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.util.Base64

/**
 * «Отправить в Point» из проводника (#252): файл, названный в командной строке, попадает в Point.
 *
 * Тонкость, ради которой это отдельный файл: Point на компьютере обычно **уже открыт**. Запускать
 * второй экземпляр на каждый пункт меню — значит плодить окна и ронять сервер, у которого занят
 * порт. Поэтому сначала стучимся в уже работающий Point по его же `/receive` — тому самому, каким
 * присылает объекты телефон, — и только если никто не ответил, запускаемся сами.
 *
 * Наружу ничего не уходит: стук идёт на `127.0.0.1`.
 */
object SendToRunning {

    /**
     * Отдать файлы работающему Point.
     *
     * `true` — всё отдано и запускаться незачем. `false` — живого Point нет, работаем сами.
     */
    fun handOff(files: List<File>, config: PcConfig): Boolean {
        if (files.isEmpty()) return false
        val port = livePort(config) ?: return false
        return files.filter { it.isFile }.all { send(it, port, config.token) }
    }

    /**
     * На каком порту отвечает живой Point.
     *
     * Перебор — не лень, а следствие того, как поднимается сервер: занятый порт он сдвигает на
     * следующий (`bind` в [PcServer]), и в конфиге остаётся желаемый, а не занятый.
     */
    private fun livePort(config: PcConfig): Int? =
        (config.port..config.port + 8).firstOrNull { candidate ->
            runCatching {
                val connection = URI("http://127.0.0.1:$candidate/caps").toURL()
                    .openConnection() as HttpURLConnection
                connection.connectTimeout = 300
                connection.readTimeout = 500
                connection.requestMethod = "GET"
                connection.setRequestProperty("X-Point-Token", config.token)
                val ok = connection.responseCode in 200..299
                connection.disconnect()
                ok
            }.getOrDefault(false)
        }

    private fun send(file: File, port: Int, token: String): Boolean = runCatching {
        val connection = URI("http://127.0.0.1:$port/receive").toURL().openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.connectTimeout = 1000
        connection.setRequestProperty("X-Point-Token", token)
        connection.setRequestProperty("X-Point-Mime", mimeFor(file.name))
        connection.setRequestProperty("X-Point-Name", b64(file.name))
        file.inputStream().use { input -> connection.outputStream.use { output -> input.copyTo(output) } }
        val ok = connection.responseCode in 200..299
        connection.disconnect()
        ok
    }.getOrDefault(false)

    private fun b64(text: String): String =
        Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8))
}

/**
 * Что делать с тем, что пришло в командной строке.
 *
 * Вынесено в чистую функцию, потому что решение здесь неочевидное и стоит теста: пустой запуск —
 * это обычное окно, а запуск с файлами — «отправить в Point», и второе окно человеку не нужно.
 */
fun filesFromArgs(args: Array<String>): List<File> =
    args.map(::File).filter { it.isFile }
