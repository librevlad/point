package com.point.checks

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Карта поверхностей в CLAUDE.md не отстаёт от манифеста (#1267).
 *
 * CLAUDE.md — то, по чему агент и новый человек узнают, чем Point касается системы. Она
 * называла три двери, а приложение объявляло пятнадцать: не было ни выделенного текста, ни
 * ссылки `/d/` от другого человека, ни печати, ни двух плиток шторки, ни стука компьютера.
 * Это не тихая поломка, а ложная карта — правя приём объекта по ней, половину дверей просто
 * не пойдёшь проверять.
 *
 * Сторож сверяет имена, а не роли: роль двери человек пишет сам, а вот появление новой двери
 * мимо карты — ровно тот способ, которым карта отстала в прошлый раз.
 *
 * Живёт в `:checks` (#1293): читаются манифест `:app` и файл в корне проекта, а модуля,
 * который собирал бы оба, нет.
 */
class EveryDoorIsOnTheMapTest {

    @Test
    fun `каждая дверь приложения названа в карте поверхностей`() {
        val map = File(repo, MAP).readText()

        val missing = doors().filterNot { it.substringAfterLast('.') in map }

        assertTrue(
            "дверь объявлена в манифесте, а в карте $MAP её нет — по такой карте человек не " +
                "пойдёт её проверять:\n" + missing.joinToString("\n"),
            missing.isEmpty(),
        )
    }

    @Test
    fun `сторож правда прочитал манифесты, а не пустоту`() {
        val doors = doors()

        assertTrue("дверей не нашлось вовсе: ${manifests().map { it.absolutePath }}", doors.size > 10)
        assertTrue("не прочитан debug-манифест: $doors", doors.any { it.endsWith("SandboxActivity") })
        assertTrue(
            "класс приложения дверью не является и в карте ему делать нечего",
            doors.none { it.endsWith("PointApplication") },
        )
    }

    @Test
    fun `сторож видит дверь, которой нет в карте`() {
        val manifest = """
            <manifest>
                <application android:name=".PointApplication">
                    <activity android:name=".HomeActivity" android:exported="true" />
                    <service android:name=".knock.KnockService" android:exported="false" />
                </application>
            </manifest>
        """.trimIndent()

        val map = "Поверхности: значок приложения — `HomeActivity`."

        val doors = doorsIn(manifest)

        assertEquals(
            "дверьми названы обе объявленные, класс приложения — нет",
            listOf(".HomeActivity", ".knock.KnockService"),
            doors,
        )
        assertEquals(
            "незаписанной названа ровно та, которой в карте нет",
            listOf(".knock.KnockService"),
            doors.filterNot { it.substringAfterLast('.') in map },
        )
    }

    private fun manifests(): List<File> = listOf(
        File(repo, "app/src/main/AndroidManifest.xml"),
        File(repo, "app/src/debug/AndroidManifest.xml"),
    )

    private fun doors(): List<String> = manifests()
        .filter { it.isFile }
        .flatMap { doorsIn(it.readText()) }
        .distinct()

    /**
     * `android:name` самого `<application>` — класс приложения, а не дверь, поэтому читается
     * только то, что объявлено ВНУТРИ тега: иначе сторож требовал бы места на карте для того,
     * чем систему не позовёшь.
     */
    private fun doorsIn(manifest: String): List<String> {
        val open = manifest.indexOf("<application")
        if (open < 0) return emptyList()
        val inside = manifest.substring(manifest.indexOf('>', open) + 1)
        return COMPONENT.findAll(inside).map { it.groupValues[1] }.distinct().toList()
    }

    private companion object {

        const val MAP = "CLAUDE.md"

        val COMPONENT = Regex("""android:name="(\.[A-Za-z0-9_.]+)"""")
    }
}
