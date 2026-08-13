package com.point.executors

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Действие, которое отдаёт объект другому устройству, тому устройству не рекламируется (#920).
 *
 * Владелец увидел на экране: «На компьютер · телефон пока не выполняет просьбы с компьютера ·
 * на телефоне». Объект уже на компьютере — предлагать отправить его туда бессмысленно, а
 * рядом стояла причина, объясняющая границу связки, к которой это действие не имеет
 * отношения.
 *
 * Это второй случай одной беды: у компьютера «На телефон» уже помечено `localOnly` — там оно
 * звучало «На телефон на ПК». Здесь то же самое зеркально.
 */
class NoActionAboutOtherDeviceTest {

    @Test
    fun `«На компьютер» не рекламируется компьютеру`() {
        val source = File("src/main/kotlin/com/point/executors/PcAction.kt").readText()
        val meta = source.substringAfter("class PcCapability").substringBefore("override fun label")

        assertTrue("телефонное «На компьютер» снова уедет на компьютер", meta.contains("localOnly = true"))
    }
}
