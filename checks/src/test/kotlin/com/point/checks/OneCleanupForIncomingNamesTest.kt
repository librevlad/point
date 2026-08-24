package com.point.checks

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Имя, пришедшее снаружи, чистят общей чисткой, а не своей (#865).
 *
 * Правило знали пятью способами в пяти местах, а в шестом не знали вовсе. Седьмое место не
 * должно завести свой способ.
 *
 * Живёт в `:checks` (#1293): проверка читает файлы `:data`, `:app` и `:desktop`. Сама чистка
 * имени проверяется тестами `:core:flow`, где она объявлена.
 */
class OneCleanupForIncomingNamesTest {

    @Test
    fun `места приёма чистят имя общей чисткой, а не своей`() {
        val guilty = listOf(
            "data/src/main/kotlin/com/point/data/ScratchObjectStore.kt",
            "app/src/main/kotlin/com/point/ClipboardSyncActivity.kt",
            "desktop/src/main/kotlin/com/point/desktop/Inbox.kt",
        ).filterNot { File(repo, it).readText().contains("safeFileName(") }

        assertTrue("своя чистка имени: $guilty", guilty.isEmpty())
    }
}
