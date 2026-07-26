package com.point.data

import com.point.core.flow.ChosenApp
import com.point.core.model.ObjectKind
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * #66 slice 4: the store behind per-app capabilities. Picks are remembered per kind,
 * newest first, re-picking bubbles an app up, and the list is capped so the graph
 * never floods with one-off choices.
 */
class FileChosenAppsTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun store() = FileChosenApps(tmp.root)

    private fun app(pkg: String, kind: ObjectKind = ObjectKind.IMAGE) =
        ChosenApp(kind, pkg, "$pkg.Main", label = pkg.substringAfterLast('.'))

    @Test
    fun `starts empty`() {
        assertTrue(store().all().isEmpty())
    }

    @Test
    fun `remembers picks newest first and survives a new instance`() = runTest {
        val s = store()
        s.record(app("com.a"))
        s.record(app("com.b"))

        assertEquals(listOf("com.b", "com.a"), store().all().map { it.packageName })
    }

    @Test
    fun `re-picking an app moves it to the front, not duplicates`() = runTest {
        val s = store()
        s.record(app("com.a"))
        s.record(app("com.b"))
        s.record(app("com.a"))

        assertEquals(listOf("com.a", "com.b"), s.all().map { it.packageName })
    }

    @Test
    fun `keeps at most four apps per kind`() = runTest {
        val s = store()
        repeat(6) { i -> s.record(app("com.app$i")) }

        val kept = s.all().filter { it.kind == ObjectKind.IMAGE }
        assertEquals(4, kept.size)
        assertEquals("com.app5", kept.first().packageName) // newest survives
    }

    @Test
    fun `kinds are independent`() = runTest {
        val s = store()
        s.record(app("com.img", ObjectKind.IMAGE))
        s.record(app("com.pdf", ObjectKind.PDF))

        assertEquals(listOf("com.img"), s.all().filter { it.kind == ObjectKind.IMAGE }.map { it.packageName })
        assertEquals(listOf("com.pdf"), s.all().filter { it.kind == ObjectKind.PDF }.map { it.packageName })
    }
}
