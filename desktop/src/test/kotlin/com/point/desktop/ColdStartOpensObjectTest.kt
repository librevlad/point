package com.point.desktop

import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Запуск с файлом выводит окно на сам объект, а не на список (#1019, DSK-001; решение
 * владельца 20.08.2026, вариант B).
 *
 * Владелец 17.08.2026: `Point.exe dsk001-proba.txt` — окно вышло, но на список, объект не
 * открыт. Корень — в самом экране: стартовое значение «уже виденного верха списка» бралось
 * из списка, и объект, принятый раньше, чем экран собрался, считался виденным.
 *
 * Проверяется тем, что видит человек: на чём стоит экран. Esc — шаг назад, и он же говорит,
 * на объекте окно или на списке.
 */
class ColdStartOpensObjectTest {

    /**
     * Ловит DSK-001: объект принимается раньше, чем собран экран, — ровно как в `main`, — и
     * экран обязан стоять на нём. След «нового» снят, первый Esc ведёт назад к списку, и
     * лишь второй прячет окно.
     */
    @Test
    fun `холодный старт с файлом открывает сам объект, а не список`() {
        val state = desktopState()
        val item = textArrival(id = "позвали")
        state.onReceived(item, ObjectSource.LOCAL)

        val hidden = AtomicInteger(0)
        val scene = compactScene(state, onHide = { hidden.incrementAndGet() })
        try {
            scene.frames()
            assertFalse(
                "объект, принятый до сборки экрана, не открылся — след «нового» не снят",
                item.obj.id in state.fresh.value,
            )

            scene.sendKeyEvent(escapeKey())
            scene.frames()
            assertEquals("первый Esc спрятал окно — экран стоял на списке, а не на объекте", 0, hidden.get().toLong())

            scene.sendKeyEvent(escapeKey())
            scene.frames()
            assertEquals("второй Esc — уже со списка — окно прячет", 1, hidden.get().toLong())
        } finally {
            scene.close()
        }
    }

    /**
     * Вторая половина того же обещания: пустой старт открывать нечего. Сторож на будущее —
     * чтобы «открывать принятое до сборки» не превратилось в «открывать что-нибудь».
     */
    @Test
    fun `холодный старт без файла оставляет окно на списке`() {
        val hidden = AtomicInteger(0)
        val scene = compactScene(desktopState(), onHide = { hidden.incrementAndGet() })
        try {
            scene.frames()
            scene.sendKeyEvent(escapeKey())
            scene.frames()
            assertEquals("без объекта первый же Esc обязан прятать окно — экран стоит на списке", 1, hidden.get().toLong())
        } finally {
            scene.close()
        }
    }
}
