package com.point.desktop

import com.point.core.flow.PointServer

/**
 * Живой сервер для тестов, которые правда ходят по сети (#473).
 *
 * Раньше они брали адрес и **общий пароль приложения** из сгенерированного `RelayEnv`, то есть
 * зависели от вещи, которой в мире с аккаунтами не существует. Теперь то же самое приезжает
 * свойствами задачи `test` (`desktop/build.gradle.kts`), а значит не компилируется никуда и в
 * артефакт попасть не может — тот же способ, которым `:bot` отдаёт свои токены.
 *
 * Ни свойства нет — тесты сами себя пропускают, ровно как пропускались на CI раньше.
 */
object LiveServer {

    /** База сервера Point для живого прогона; пусто — прогонять нечем. */
    val url: String
        get() = System.getProperty("point.test.server")?.takeIf { it.isNotBlank() }
            ?.let { PointServer.base(it) }
            .orEmpty()

    /** Пропуск тестового аккаунта — то, чем устройство представляется серверу. */
    val pass: String
        get() = System.getProperty("point.test.pass").orEmpty()

    /** Есть ли чем ходить: и адрес, и пропуск. */
    val configured: Boolean get() = url.isNotBlank() && pass.isNotBlank()
}
