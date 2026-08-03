package com.point

import com.point.core.model.ObjectKind
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Что делает тап по самому крупному элементу экрана.
 *
 * Прежнее правило требовало слой слов: обвести картинку можно было только после чтения. Возражение
 * владельца по существу — выделение для того и нужно, чтобы указать область, и распознавание тут
 * не условие, а одно из возможных продолжений.
 */
class HeroTapTest {

    @Test
    fun `картинку можно обвести и без чтения`() {
        assertEquals(HeroTap.SELECT, heroTapOf(ObjectKind.IMAGE, hasWordLayer = false))
    }

    @Test
    fun `прочитанную картинку тоже обводят — рамка липнет к словам`() {
        assertEquals(HeroTap.SELECT, heroTapOf(ObjectKind.IMAGE, hasWordLayer = true))
    }

    @Test
    fun `у текста обводить нечего — тап открывает объект`() {
        assertEquals(HeroTap.OPEN, heroTapOf(ObjectKind.TEXT, hasWordLayer = true))
    }

    @Test
    fun `архив тап открывает, а не обводит`() {
        assertEquals(HeroTap.OPEN, heroTapOf(ObjectKind.ZIP, hasWordLayer = false))
    }
}
