package com.point.desktop

import com.point.core.flow.Capability
import com.point.core.flow.CapabilityMeta
import com.point.core.flow.Realizer
import com.point.core.flow.Resolver
import com.point.core.model.ActionResult
import com.point.core.model.CapabilityId
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ResultObject
import com.point.core.model.ScratchRef
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ChainOnPcTest {

    @get:Rule val temp = TemporaryFolder()

    private class Says(id: String, private val name: String) : Capability {
        override val id = CapabilityId(id)
        override val icon = "x"
        override val meta = CapabilityMeta()
        override fun label(state: ObjectState) = name
        override fun accepts(state: ObjectState) = true
        override fun produces(state: ObjectState) = ObjectState(ObjectKind.TEXT)
    }

    private class MakesNew(id: String, private val dir: File, private val name: String) : Realizer {
        override val capabilityId = CapabilityId(id)

        var sawText: String? = null
            private set

        override suspend fun perform(input: PointObject, amendment: String?): ActionResult {
            sawText = File(input.uri.value).readText()
            val out = File(dir, "$name.txt").apply { writeText("после: $name") }
            return ActionResult.Success(
                ResultObject(ObjectKind.TEXT, "text/plain", ScratchRef(out.absolutePath), mapOf("name" to name)),
            )
        }
    }

    private class Pick(private val byId: Map<String, Realizer>) : Resolver {
        override fun realizerFor(capabilityId: CapabilityId): Realizer = byId.getValue(capabilityId.value)
    }

    @Test fun `результат действия становится объектом на экране компьютера`() = runTest {
        val dir = temp.newFolder("out")
        val first = MakesNew("first", dir, "Первый результат")
        val inbox = Inbox(temp.newFolder("inbox"))
        val state = DesktopState(
            DesktopRegistry(setOf(Says("first", "Первый результат"))),
            Pick(mapOf("first" to first)),
            clipboard = { },
            reopenPath = { path -> File(path).takeIf(File::isFile)?.let { inbox.addFile(it.absolutePath) } },
        )
        val source = temp.newFile("исходный.txt").apply { writeText("исходный текст") }
        state.onReceived(inbox.addFile(source.absolutePath))

        state.onBubble(state.items.value.single(), state.bubblesFor(state.items.value.single()).single())

        waitUntil { state.items.value.size == 2 }

        val born = state.items.value.first { it.obj.uri.value.endsWith("Первый результат.txt") }
        assertEquals("Первый результат", born.obj.metadata["name"])
    }

    @Test fun `второе действие работает с результатом первого, а не с исходным объектом`() = runTest {

        val dir = temp.newFolder("out")
        val first = MakesNew("first", dir, "Первый результат")
        val second = MakesNew("second", dir, "Второй результат")
        val inbox = Inbox(temp.newFolder("inbox"))
        val state = DesktopState(
            DesktopRegistry(setOf(Says("first", "Первый результат"), Says("second", "Второй результат"))),
            Pick(mapOf("first" to first, "second" to second)),
            clipboard = { },
            reopenPath = { path -> File(path).takeIf(File::isFile)?.let { inbox.addFile(it.absolutePath) } },
        )
        state.onReceived(inbox.addFile(temp.newFile("исходный2.txt").apply { writeText("исходный текст") }.absolutePath))

        val start = state.items.value.single()
        state.onBubble(start, state.bubblesFor(start).first { it.capabilityId.value == "first" })
        waitUntil { state.items.value.size == 2 }

        val born = state.items.value.first { it.obj.uri.value.endsWith("Первый результат.txt") }
        state.onBubble(born, state.bubblesFor(born).first { it.capabilityId.value == "second" })
        waitUntil { second.sawText != null }

        assertTrue(
            "второе действие взяло исходный объект, а не результат первого: " + second.sawText,
            second.sawText.orEmpty().startsWith("после: Первый результат"),
        )
        assertTrue("второму достался исходный текст", second.sawText != "исходный текст")
    }

    @Test fun `объект, которого нет на диске, на экран не попадает`() = runTest {

        val inbox = Inbox(temp.newFolder("inbox3"))
        val liar = object : Realizer {
            override val capabilityId = CapabilityId("liar")
            override suspend fun perform(input: PointObject, amendment: String?) = ActionResult.Success(
                ResultObject(ObjectKind.TEXT, "text/plain", ScratchRef(File(temp.root, "нет-такого.txt").absolutePath)),
            )
        }
        val state = DesktopState(
            DesktopRegistry(setOf(Says("liar", "Врун"))),
            Pick(mapOf("liar" to liar)),
            clipboard = { },
            reopenPath = { path -> File(path).takeIf(File::isFile)?.let { inbox.addFile(it.absolutePath) } },
        )
        state.onReceived(inbox.addFile(temp.newFile("исходный3.txt").apply { writeText("текст") }.absolutePath))

        state.onBubble(state.items.value.single(), state.bubblesFor(state.items.value.single()).single())
        Thread.sleep(300)

        assertEquals("на экране появился объект без файла", 1, state.items.value.size)
    }

    private fun waitUntil(timeoutMs: Long = 3_000, condition: () -> Boolean) {
        val until = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < until) {
            if (condition()) return
            Thread.sleep(20)
        }
        assertTrue("не дождались: условие не выполнилось за $timeoutMs мс", condition())
    }
}
