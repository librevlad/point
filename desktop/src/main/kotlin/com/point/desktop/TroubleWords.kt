package com.point.desktop

import java.io.File

/**
 * Беда на компьютере говорит словами, а не именем класса (#822).
 *
 * Живой прогон 12.08.2026: тап по «Не прятать окно — принесу файл» открыл системное окно
 * `Error` с текстом `com/point/desktop/ui/CompactAppKt$CompactApp$18$2$11$1$1`. Человеку
 * показали внутреннее имя лямбды — это не сообщение, а след, оставленный для нас.
 *
 * Здесь беда переводится на человеческий и получает совет. Подробности не пропадают: они
 * уходят в файл рядом с настройками, чтобы разбор был возможен.
 */
fun troubleWords(error: Throwable): String = when {

    // Классов не хватает ровно тогда, когда работает старая установка поверх нового кода:
    // у владельца стоял Point от 6 августа, а действие приехало 10-го.
    error is NoClassDefFoundError || error is ClassNotFoundException ->
        "Не нашли часть приложения — похоже, установлена старая версия. Обновите Point на компьютере"

    error is OutOfMemoryError -> "Не хватило памяти на эту работу — попробуйте объект поменьше"

    error is java.io.FileNotFoundException -> "Файла не нашлось на месте — возможно, его убрали"

    error is java.net.UnknownHostException || error is java.net.ConnectException ->
        "Сервер не ответил — проверьте подключение"

    else -> "Что-то пошло не так — работа не выполнена"
}

/**
 * След беды — на диск, а не человеку на экран. Файл один и переписывается: он нужен для
 * разбора здесь и сейчас, а не как архив.
 */
fun keepTrouble(dir: File, error: Throwable) {
    runCatching {
        dir.mkdirs()
        File(dir, "trouble.txt").writeText(
            buildString {
                appendLine("Point ${BuildInfo.VERSION}, сборка ${BuildInfo.BUILT_ON}")
                appendLine(error.toString())
                error.stackTrace.take(STACK_LINES).forEach { appendLine("    at $it") }
            },
        )
    }
}

private const val STACK_LINES = 30
