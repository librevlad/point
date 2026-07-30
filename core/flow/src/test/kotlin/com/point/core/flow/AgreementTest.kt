package com.point.core.flow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Voting on a value rather than on a table cell (#222, шаг 7).
 *
 * The mechanics come straight out of [reconcile] and are covered there too; what is new is that
 * they now apply wherever two sources read the same thing — and that a disagreement is *kept*
 * instead of resolved into a value that looks as settled as any other.
 */
class AgreementTest {

    @Test
    fun `nothing read is not a disagreement`() {
        assertNull(agree(emptyList()))
        assertNull(agree(listOf("", "   ")))
    }

    @Test
    fun `one reading is agreement with itself`() {
        val v = agree(listOf("вул. Хрещатик, 1"))!!

        assertEquals("вул. Хрещатик, 1", v.value)
        assertTrue(v.agreed)
        assertTrue(v.candidates.isEmpty())
    }

    @Test
    fun `format noise is not a disagreement`() {
        // Case, spacing, dashes and punctuation fold away — otherwise every source would
        // "disagree" with every other and the flag would mean nothing.
        val v = agree(listOf("+380 67 123-45-67", "+380671234567", "+380 67 123 45 67"))!!

        assertTrue(v.agreed)
        assertEquals("+380 67 123-45-67", v.value) // the first raw form, never a normalised one
    }

    @Test
    fun `two of three agreeing wins the vote, and the loser is kept`() {
        val v = agree(listOf("Хрещатик 1", "Хрещатик 7", "Хрещатик, 1"))!!

        assertEquals("Хрещатик 1", v.value)
        assertFalse(v.agreed)
        assertEquals(listOf("Хрещатик 1", "Хрещатик 7", "Хрещатик, 1"), v.candidates)
    }

    @Test
    fun `three sources all disagreeing gives a flag and three candidates`() {
        val v = agree(listOf("A", "B", "C"))!!

        assertFalse(v.agreed)
        assertEquals(3, v.candidates.size)
    }

    @Test
    fun `on a tie the first reading wins, so the caller controls precedence by order`() {
        assertEquals("известное", agree(listOf("известное", "свежее"))!!.value)
        assertEquals("свежее", agree(listOf("свежее", "известное"))!!.value)
    }

    // --- Merging what is known with what just arrived ---

    @Test
    fun `a fact nobody knew is simply taken`() {
        val merged = mergeFacts(emptyMap(), mapOf("entity.phone" to "+380671234567"))

        assertEquals("+380671234567", merged["entity.phone"])
        assertTrue(alternativesOf(merged, "entity.phone").isEmpty())
    }

    @Test
    fun `sources that agree leave no trace of a dispute`() {
        val merged = mergeFacts(
            mapOf("entity.phone" to "+380 67 123 45 67"),
            mapOf("entity.phone" to "+380671234567"),
        )

        assertEquals("+380 67 123 45 67", merged["entity.phone"])
        assertTrue(alternativesOf(merged, "entity.phone").isEmpty())
    }

    @Test
    fun `a later source no longer wins by simply arriving second`() {
        // This is the behaviour that changed: deep-understand used to overwrite the on-device
        // reading outright, and nothing recorded that the two had ever differed.
        val merged = mergeFacts(
            mapOf("entity.address" to "вул. Хрещатик, 1"),
            mapOf("entity.address" to "вул. Хрещатик, 7"),
        )

        assertEquals("вул. Хрещатик, 1", merged["entity.address"])
        assertEquals(
            listOf("вул. Хрещатик, 1", "вул. Хрещатик, 7"),
            alternativesOf(merged, "entity.address"),
        )
    }

    @Test
    fun `agreeing later clears an earlier dispute`() {
        val disputed = mergeFacts(mapOf("entity.address" to "A"), mapOf("entity.address" to "B"))

        val settled = mergeFacts(disputed, mapOf("entity.address" to "A"))

        assertEquals("A", settled["entity.address"])
        assertTrue(alternativesOf(settled, "entity.address").isEmpty())
    }

    @Test
    fun `a third disagreeing source adds to the candidates, it does not replace them`() {
        val first = mergeFacts(mapOf("entity.address" to "A"), mapOf("entity.address" to "B"))

        val second = mergeFacts(first, mapOf("entity.address" to "C"))

        assertEquals(listOf("A", "B", "C"), alternativesOf(second, "entity.address"))
    }

    @Test
    fun `stored alternatives are never themselves merged as facts`() {
        val merged = mergeFacts(
            mapOf("entity.address" to "A"),
            mapOf("entity.address$META_ALT_SUFFIX" to "мусор"),
        )

        assertEquals("A", merged["entity.address"])
        assertTrue(alternativesOf(merged, "entity.address").isEmpty())
    }

    @Test
    fun `other facts are carried through untouched`() {
        val merged = mergeFacts(
            mapOf("name" to "чек.jpg", "entity.phone" to "+380671234567"),
            mapOf("entity.date" to "завтра"),
        )

        assertEquals("чек.jpg", merged["name"])
        assertEquals("+380671234567", merged["entity.phone"])
        assertEquals("завтра", merged["entity.date"])
    }

    @Test
    fun `the stored form survives a round trip through one separator`() {
        // One place knows the separator: a platform line separator would make the journal
        // unreadable on the paired PC.
        val stored = mapOf("k$META_ALT_SUFFIX" to altValue(listOf("A", "B", "C")))

        assertEquals(listOf("A", "B", "C"), alternativesOf(stored, "k"))
    }
}
