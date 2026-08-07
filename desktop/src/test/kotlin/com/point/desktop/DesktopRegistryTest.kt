package com.point.desktop

import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import org.junit.Assert.assertEquals
import org.junit.Test

class DesktopRegistryTest {

    private val registry = DesktopRegistry(
        setOf(PcOpenCapability(), PcRevealCapability(), PcCopyCapability(), PcSaveAsCapability()),
    )

    @Test
    fun `an image gets open, copy, reveal and save — in priority order`() {

        assertEquals(
            listOf("Открыть", "Копировать картинку", "Показать в папке", "Сохранить в…"),
            registry.bubblesFor(ObjectState(ObjectKind.IMAGE)).map { it.title },
        )
    }

    @Test
    fun `text additionally gets copy, ranked between open and reveal`() {
        assertEquals(
            listOf("Открыть", "Копировать", "Показать в папке", "Сохранить в…"),
            registry.bubblesFor(ObjectState(ObjectKind.TEXT)).map { it.title },
        )
    }
}
