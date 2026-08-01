package com.point.core.flow

import kotlin.test.Test

/** ВРЕМЕННЫЙ репро-тест для ревью (#297). Удаляется после прогона. */
class TmpReproConfusableDigitTest {

    private fun show(known: String, fresh: String) {
        println("isRepairOf(\"$known\", \"$fresh\") = ${isRepairOf(known, fresh)}")
    }

    @Test
    fun `repro isRepairOf on house numbers glued to the street word`() {
        println("---- isRepairOf ----")
        show("вул. Шевченка, буд.10", "вул. Шевченка, буд.13")
        show("Хрещатик,10", "Хрещатик,30")
        show("м. Київ, вул. Лесі Українки,10", "м. Київ, вул. Лесі Українки,30")
        println("-- with a space before the number (claimed protected) --")
        show("Хрещатик, 10", "Хрещатик, 13")
        show("вул. Шевченка, буд. 10", "вул. Шевченка, буд. 13")
        println("-- symmetry: corrupted reading passing as a repair of a clean one --")
        show("Іваненко Іван Петрович", "3ваненко Іван Петрович")
        show("3ваненко Іван Петрович", "Іваненко Іван Петрович")
        println("-- controls from the shipped test suite --")
        show("Олексйвка, вул. Сонячна, 15", "Олексіївка, вул. Сонячна, 15")
        show("+380671234567", "+380671234568")
    }

    @Test
    fun `repro mergeFacts silently overwrites the known house number`() {
        println("---- mergeFacts ----")
        val known = mapOf(
            "entity.address" to "вул. Шевченка, буд.10",
            "entity.address.alt" to altValue(listOf("вул. Шевченка, буд.10", "вул. Шевченко, буд.10")),
        )
        val merged = mergeFacts(known, mapOf("entity.address" to "вул. Шевченка, буд.13"))
        println("known   = ${known["entity.address"]}")
        println("fresh   = вул. Шевченка, буд.13")
        println("merged  = ${merged["entity.address"]}")
        println("alt     = ${alternativesOf(merged, "entity.address")}")
        println("alt key present = ${merged.containsKey("entity.address" + META_ALT_SUFFIX)}")

        println("---- mergeFacts, plain case with no prior dispute ----")
        val plain = mergeFacts(
            mapOf("entity.address" to "м. Київ, вул. Лесі Українки,10"),
            mapOf("entity.address" to "м. Київ, вул. Лесі Українки,30"),
        )
        println("merged  = ${plain["entity.address"]}")
        println("alt     = ${alternativesOf(plain, "entity.address")}")
    }

    @Test
    fun `repro token dominance that unlocks the fold`() {
        println("---- token letter-vs-digit dominance ----")
        listOf("буд.10", "хрещатик,10", "10", "буд. 10", "дом7", "№9", "кв.101", "буд.100").forEach { t ->
            val letters = t.count(Char::isLetter)
            val digits = t.count(Char::isDigit)
            println("token=\"$t\" letters=$letters digits=$digits folded=${letters > digits}")
        }
    }
}
