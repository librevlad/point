package com.point.core.flow

import kotlin.test.Test

class TmpSkepticProbeTest {
    @Test
    fun probe() {
        val cases = listOf(
            "вул. Шевченка, буд.10" to "вул. Шевченка, буд.13",
            "Хрещатик,10" to "Хрещатик,30",
            "м. Київ, вул. Лесі Українки,10" to "м. Київ, вул. Лесі Українки,30",
            "Хрещатик, 10" to "Хрещатик, 13",
            "Іваненко Іван Петрович" to "3ваненко Іван Петрович",
            "вул. Хрещатик, буд.1" to "вул. Хрещатик, буд.3",
            "кв.10, вул. Сонячна" to "кв.13, вул. Сонячна",
            "Відділення №10 Хрещатик" to "Відділення №13 Хрещатик",
            "рахунок UA103000010" to "рахунок UA103000013",
            "Договір №100/1 від 2020" to "Договір №300/1 від 2020",
        )
        cases.forEach { (known, fresh) ->
            println("PROBE isRepairOf(\"$known\", \"$fresh\") = ${isRepairOf(known, fresh)}")
        }
        val merged = mergeFacts(
            mapOf("entity.address" to "вул. Шевченка, буд.10"),
            mapOf("entity.address" to "вул. Шевченка, буд.13"),
        )
        println("PROBE merged = $merged")
        val disputed = mergeFacts(
            mapOf(
                "entity.address" to "вул. Шевченка, буд.10",
                "entity.address.alt" to "вул. Шевченка, буд.10\nвул. Шевченка, буд.12",
            ),
            mapOf("entity.address" to "вул. Шевченка, буд.13"),
        )
        println("PROBE disputed-then-repair = $disputed")
    }
}
