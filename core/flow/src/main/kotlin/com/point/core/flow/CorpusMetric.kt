package com.point.core.flow

data class CorpusCase(

    val frame: String,

    val expectedAction: String,

    val facts: Map<String, String>,

    val outOfCount: OutOfCount? = null,
)

enum class OutOfCount(val word: String, val note: String) {
    TABLE("таблица", "меряются не здесь, а по самой таблице, которую получил человек"),
    REFUSED("отказ", "проверять не будем — решение и причина записаны в docs/CORPUS.md"),
    AWAITING("ждёт схемы", "ждут описания успеха"),
    ;

    companion object {

        fun byWord(word: String): OutOfCount = entries.firstOrNull { it.word == word }
            ?: error("«$word» — не причина; годятся: ${entries.joinToString { it.word }}")
    }
}

data class UnscoredFrame(val frame: String, val reason: OutOfCount?)

data class CorpusScore(
    val ready: List<String>,
    val notReady: List<String>,

    val unscored: List<UnscoredFrame>,
) {
    val scored: Int get() = ready.size + notReady.size

    val share: Double? get() = if (scored == 0) null else ready.size.toDouble() / scored

    fun outOfCount(reason: OutOfCount): List<String> =
        unscored.filter { it.reason == reason }.map { it.frame }

    val unnamed: List<String> get() = unscored.filter { it.reason == null }.map { it.frame }
}

fun scoreCorpus(cases: List<CorpusCase>, schemas: List<ActionSchema> = ACTION_SCHEMAS): CorpusScore {
    val ready = mutableListOf<String>()
    val notReady = mutableListOf<String>()
    val unscored = mutableListOf<UnscoredFrame>()
    cases.forEach { case ->
        val schema = schemas.firstOrNull { it.id == case.expectedAction }
        when {
            schema == null -> unscored += UnscoredFrame(case.frame, case.outOfCount)
            schema.readiness(case.facts) is Readiness.Ready -> ready += case.frame
            else -> notReady += case.frame
        }
    }
    return CorpusScore(ready, notReady, unscored)
}
