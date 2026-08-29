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

/**
 * Исходник без комментариев (#1333).
 *
 * Проверки этого модуля читают проект текстом, а комментарий нарочно цитирует то, чего в коде
 * быть не должно: «прежде здесь стояло `?: rgba`». Считай его кодом — и сторож падал бы на
 * объяснении, почему он существует.
 */
fun code(text: String): String =
    text.replace(BLOCK_COMMENT, " ").lines().joinToString("\n") { it.substringBefore("//") }

private val BLOCK_COMMENT = Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL)
