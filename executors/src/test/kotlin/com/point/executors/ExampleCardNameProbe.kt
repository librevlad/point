package com.point.executors

import com.point.core.flow.UnderstandRealizer

import com.point.core.flow.ContactInserter
import com.point.core.flow.EntityExtractor
import com.point.core.flow.NewContact
import com.point.core.flow.isKnowledgeKey
import com.point.core.flow.mergeKnowledge
import com.point.core.model.ActionResult
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Живой прогон визитки, с которой заведена карточка #993: имя доезжает до системной
 * карточки контакта.
 *
 * Кадр — не выдумка под тест, а собственный пример Point: `app/src/main/res/raw/example_card.jpg`,
 * тот самый объект, что открывается с домашнего экрана карточкой «попробовать». Путь тот же,
 * что у человека: «Понять» настоящей цепочкой бесплатных моделей → знание витка ложится в
 * объект → «Сохранить контакт» → что уехало в NewContact.
 *
 * Проба с настоящей сетью, в счёте CI не участвует: без POINT_CARD_PROBE тест пропускается.
 *
 *   POINT_CARD_PROBE=1 ./gradlew :executors:testDebugUnitTest --tests "*ExampleCardNameProbe*"
 */
class ExampleCardNameProbe {

    @Test
    fun `имя с примерной визитки доезжает до карточки контакта`() {
        assumeTrue(System.getenv("POINT_CARD_PROBE") != null)
        runBlocking {
            val root = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
                .first { File(it, "local.properties").isFile }
            val frame = File(root, "app/src/main/res/raw/example_card.jpg")
            var obj = PointObject(
                "example_card", "image/jpeg", ScratchRef(frame.absolutePath), ObjectState(ObjectKind.IMAGE),
            )

            val realizer = UnderstandRealizer(EyesOnlyCorpusProbe.liveChain())
            repeat(ROUNDS) { round ->
                when (val outcome = runCatching { realizer.perform(obj, null) }.getOrNull()) {
                    is ActionResult.Done -> {
                        println("виток ${round + 1}: ${outcome.message}")
                        outcome.findings?.let { obj = obj.copy(metadata = mergeKnowledge(obj.metadata, it.metadata)) }
                    }
                    else -> println("виток ${round + 1}: ${(outcome as? ActionResult.Failure)?.reason ?: "нет ответа"}")
                }
            }
            obj.metadata.filterKeys(::isKnowledgeKey).forEach { (key, value) -> println("  $key=$value") }

            val inserter = object : ContactInserter {
                var contact: NewContact? = null
                override suspend fun insertContact(contact: NewContact) {
                    this.contact = contact
                }
            }
            val silent = object : EntityExtractor {
                override suspend fun extract(text: String) = emptyList<com.point.core.flow.Entity>()
            }
            val said = SaveContactRealizer(silent, inserter).perform(obj, null)
            println("«Сохранить контакт» → ${(said as? ActionResult.Done)?.message ?: said}")
            println("в системную карточку: ${inserter.contact}")

            assertNotNull("имя не доехало до системной карточки", inserter.contact?.name)
        }
    }

    private companion object { const val ROUNDS = 2 }
}
