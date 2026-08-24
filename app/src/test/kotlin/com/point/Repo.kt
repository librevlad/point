package com.point

import java.io.File

/**
 * Корень проекта (#1301).
 *
 * Ищется поиском вверх до `settings.gradle.kts`, а не отсчётом уровней от каталога модуля:
 * путь вида `../..` верен ровно для той глубины, на которой файл написали, и при первом же
 * переносе ломается молча — тест либо не находит файл, либо читает не тот.
 */
val repo: File = generateSequence(File(".").absoluteFile) { it.parentFile }
    .first { File(it, "settings.gradle.kts").isFile }
