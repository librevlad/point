package com.point.checks

import java.io.File

/**
 * Корень проекта (#1293).
 *
 * Проверки этого модуля читают файлы всего проекта, поэтому им нужен его корень — и берётся
 * он поиском вверх до `settings.gradle.kts`, а не отсчётом уровней вверх от каталога модуля.
 * Отсчёт ломался молча при первом же переносе файла: путь `../..` верен ровно для той
 * глубины, на которой файл написали.
 */
val repo: File = generateSequence(File(".").absoluteFile) { it.parentFile }
    .first { File(it, "settings.gradle.kts").isFile }
