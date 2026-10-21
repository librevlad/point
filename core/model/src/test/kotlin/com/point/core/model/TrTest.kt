package com.point.core.model

import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrTest {

    private val pinned = System.getProperty(Lang.PROPERTY)

    @After
    fun restore() {
        if (pinned == null) System.clearProperty(Lang.PROPERTY) else System.setProperty(Lang.PROPERTY, pinned)
    }

    private fun speak(lang: Lang) {
        System.setProperty(Lang.PROPERTY, lang.tag)
    }

    @Test
    fun `language of the device picks one of three, anything else is English`() {
        assertEquals(Lang.UK, Lang.of(Locale.forLanguageTag("uk-UA")))
        assertEquals(Lang.RU, Lang.of(Locale.forLanguageTag("ru-RU")))
        assertEquals(Lang.EN, Lang.of(Locale.forLanguageTag("de-DE")))
        assertEquals(Lang.UK, Lang.of("UK-ua"))
        assertNull(Lang.of("de"))
    }

    @Test
    fun `values take their places, even in another order`() {
        speak(Lang.RU)
        assertEquals("1 / 2", tr("{0} / {1}", 1, 2))
        assertEquals("{5}", tr("{5}", 1))
    }

    @Test
    fun `missing translation falls back to the original, not to nothing`() {
        speak(Lang.EN)
        assertEquals("no such key {0}", tr("no such key {0}"))
    }

    @Test
    fun `catalog row gives each language its own cell`() {
        val table = TrCatalog.parse(sequenceOf("a\\nb\tuk\\tx\ten\\\\y", "broken line", ""))
        assertEquals(listOf("uk\tx", "en\\y"), table.getValue("a\nb").toList())
        assertEquals(1, table.size)
        assertEquals("a\\nb\\tc\\\\", TrCatalog.escape("a\nb\tc\\"))
    }

    @Test
    fun `English plural has two forms, Ukrainian and Russian have three`() {
        speak(Lang.EN)
        assertEquals("one", trPlural(1, "one", "few", "many"))
        assertEquals("many", trPlural(3, "one", "few", "many"))
        speak(Lang.UK)
        assertEquals("one", trPlural(21, "one", "few", "many"))
        assertEquals("few", trPlural(22, "one", "few", "many"))
        assertEquals("many", trPlural(12, "one", "few", "many"))
        assertEquals("many", trPlural(25, "one", "few", "many"))
    }

    @Test
    fun `the shipped catalog is found on the classpath`() {
        speak(Lang.EN)
        assertShippedRowTranslates()
    }

    private fun assertShippedRowTranslates() {
        val row = TrCatalog::class.java.getResourceAsStream(TrCatalog.RESOURCE)!!
            .bufferedReader(Charsets.UTF_8).useLines { it.first() }
        val key = TrCatalog.unescape(row.substringBefore('\t'))
        assertEquals(TrCatalog.unescape(row.split('\t')[2]), tr(key))
    }
}
