package com.point.desktop

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Явный зов человека выводит окно вперёд (#1019, решение владельца 20.08.2026, вариант B).
 *
 * Проверяется подъём окна, а не счётчик рядом с ним: сигнал зова подан — окно ОС обязано
 * получить `toFront()`. Прежде этот файл сверял значение `RaiseSignal.calls` — то самое
 * поле, которое завёл чинивший PR: ни подъёма окна, ни открытого объекта не касался ни один
 * из тестов.
 *
 * Сам выход окна на передний план — дело Windows; здесь проверено «когда зовём», предел ОС
 * («поверх всех» не возвращаем, мигание в панели задач принято) остаётся за окном.
 */
@OptIn(
    androidx.compose.ui.ExperimentalComposeUiApi::class,
    androidx.compose.ui.InternalComposeUiApi::class,
)
class RaiseOnCallTest {

    /**
     * Окно уже видимо, но погребено под чужими: повторный зов обязан поднять его снова.
     * Булев признак «показать» второй раз не срабатывает — человек жмёт и не видит ничего.
     */
    @Test
    fun `каждый зов выводит окно вперёд — и уже видимое, но погребённое, тоже`() {
        val raise = RaiseSignal()
        val front = AtomicInteger(0)
        val scene = raiseWindow(raise) { front.incrementAndGet() }
        try {
            scene.frames()
            assertEquals("без зова окно лезет вперёд само", 0, front.get().toLong())

            raise.call()
            scene.frames()
            assertEquals("зов не вывел окно вперёд", 1, front.get().toLong())

            raise.call()
            scene.frames()
            assertEquals("повторный зов потерян — погребённое окно так и не поднимется", 2, front.get().toLong())
        } finally {
            scene.close()
        }
    }

    /** Запуск с файлом зовёт раньше, чем окно собрано: зов исполняется первым кадром. */
    @Test
    fun `зов до сборки окна не пропадает`() {
        val raise = RaiseSignal()
        raise.call()

        val front = AtomicInteger(0)
        val scene = raiseWindow(raise) { front.incrementAndGet() }
        try {
            scene.frames()
            assertEquals("зов, случившийся до сборки окна, пропал", 1, front.get().toLong())
        } finally {
            scene.close()
        }
    }

    /** Только шов подъёма: сигнал зова и окно ОС, которое обязано выйти вперёд. */
    private fun raiseWindow(raise: RaiseSignal, bringToFront: () -> Unit) =
        ImageComposeScene(width = 10, height = 10, density = Density(1f)) {
            RaiseOnCall(raise, bringToFront)
        }
}
