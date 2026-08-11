package com.point.desktop

import com.point.core.flow.DropInbox
import com.point.core.flow.DropInboxBox
import com.point.core.flow.DropWait
import com.point.core.flow.receiveWaitStatus
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Приём файла по ссылке на компьютере (#727): «и на пк тоже и прием и отправка».
 *
 * Разговор с сервером — общий `HttpDropInbox` из `:core:flow`, тот же, которым принимает
 * телефон. Здесь только ожидание, слова человеку и рождение объекта.
 *
 * Подтверждение уходит **после** того, как объект создан (#726): пока его нет, файл на
 * сервере не стирается — прислал его чужой человек, и повторить он не сможет.
 */
class ReceiveOnPc(
    private val inbox: DropInbox,
    private val scope: CoroutineScope,
    private val tmpDir: File,
    private val onArrived: (name: String, mime: String, path: String) -> Unit,
    private val retryPauseMs: Long = 2_000,
) {
    data class Waiting(val link: String, val status: String, val failed: String? = null)

    private val _waiting = MutableStateFlow<Waiting?>(null)
    val waiting: StateFlow<Waiting?> = _waiting.asStateFlow()

    /** Кому рассказывать про ожидание: состояние живёт в одном месте, экран — в другом. */
    var onWaiting: ((Waiting?) -> Unit)? = null

    private var work: Job? = null

    /** Открыть ящик и ждать. Ссылку человек показывает тому, кто далеко. */
    fun start(onFailure: (String) -> Unit) {
        if (work?.isActive == true) return
        work = scope.launch(Dispatchers.IO) {
            // Отказ называет свою причину словами сервера (#729), а не одной фразой на все беды.
            val opened = inbox.open()
            if (opened !is com.point.core.flow.DropOpen.Opened) {
                onFailure((opened as com.point.core.flow.DropOpen.Refused).reason)
                return@launch
            }
            val box = opened.box
            show(Waiting(box.link, receiveWaitStatus(0)))
            wait(box)
        }
    }

    fun cancel() {
        work?.cancel()
        work = null
        show(null)
    }

    private fun show(waiting: Waiting?) {
        _waiting.value = waiting
        onWaiting?.invoke(waiting)
    }

    private suspend fun wait(box: DropInboxBox) {
        var failures = 0
        while (work?.isActive != false) {
            when (val outcome = inbox.await(box) { name -> File(tmpDir, name).absolutePath }) {
                is DropWait.Empty -> {
                    failures = 0
                    show(_waiting.value?.copy(status = receiveWaitStatus(0), failed = null))
                    delay(retryPauseMs)
                }

                is DropWait.Failed -> {
                    failures++
                    show(
                        _waiting.value?.copy(
                            status = receiveWaitStatus(failures),
                            failed = outcome.reason,
                        ),
                    )
                    delay(retryPauseMs)
                }

                is DropWait.Arrived -> {
                    val arrival = outcome.arrival
                    val file = File(arrival.path)
                    if (!file.isFile || file.length() == 0L) {
                        show(_waiting.value?.copy(failed = "Файл пришёл пустым — попросите прислать снова"))
                        delay(retryPauseMs)
                        continue
                    }
                    onArrived(arrival.name, arrival.mime, arrival.path)

                    // Объект есть — только теперь файл можно стирать на сервере.
                    runCatching { inbox.ack(box, arrival.fileId) }
                    show(null)
                    work = null
                    return
                }
            }
        }
    }
}
