package com.point.checks

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Стук компьютера досматривается на обеих дорогах (#1108).
 *
 * Стук — письмо через почту Google, а не команда «проснись»: почта берёт его и для
 * выключенного телефона. Компьютер поэтому не верит стуку на слово, а смотрит, пришёл ли
 * телефон за просьбой, и молчание называет словами.
 *
 * Дорог у стука две — просьба о работе и уехавший объект, — и вторая держится одной строкой
 * проводки в `Main.kt`. Тестами эта строка не покрыта ничем: `Main.kt` — точка входа
 * приложения, его никто не запускает под тестом. Возврат её к прежнему `account::knockPhones`
 * вернул бы прежнее молчание при полностью зелёном прогоне — поэтому сторож текстовый, как
 * и другие сторожа фактов проводки в этом модуле.
 */
class KnockIsWatchedTest {

    private val main =
        File(repo, "desktop/src/main/kotlin/com/point/desktop/Main.kt").readText()

    @Test
    fun `стук вслед уехавшему объекту идёт через досмотр компьютера`() {
        val wiring = main.lineSequence()
            .map(String::trim)
            .filter { it.startsWith("knockPhoneLate =") }
            .toList()

        assertEquals("проводка стука вслед объекту потерялась: $wiring", 1, wiring.size)
        assertTrue(
            "стук уехавшего объекта снова уходит мимо досмотра: ${wiring.single()}",
            wiring.single().contains("knockAfterSending"),
        )
    }
}
