package com.point.executors

import com.point.core.flow.reconcile
import org.junit.Test

class ZzProbeAnchorTest {
    @Test
    fun probe() {
        // A пропустила среднюю строку («Овес»), у неё спор с атомами о дате строки «Пшено».
        val a = listOf(
            listOf("16.07", "Гречка", "42"),
            listOf("16.07⚠", "Пшено", "53"), // ⚠ — спор модели с её же атомами
        )
        val b = listOf(
            listOf("16.07", "Гречка", "42"),
            listOf("16.07", "Овес", "31"),
            listOf("16.07", "Пшено", "53"),
        )
        val consensus = reconcile(listOf(a, b))
        println("PROBE rows=" + consensus.rows)
        println("PROBE consensusCandidates=" + consensus.candidates)
        val key = 1 to 0
        println("PROBE anchor(1,0)=" + anchorCandidates(key, listOf("16.07", "1б.07"), consensus.rows))
        println("PROBE anchor(1,2)=" + anchorCandidates(1 to 2, listOf("53", "5З"), consensus.rows))
        println("PROBE anchor(1,1)=" + anchorCandidates(1 to 1, listOf("Пшено", "Пшенo"), consensus.rows))
    }
}
