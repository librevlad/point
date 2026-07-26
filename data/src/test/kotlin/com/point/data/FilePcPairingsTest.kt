package com.point.data

import com.point.core.flow.PcPairing
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FilePcPairingsTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `starts empty, saves, survives a new instance, clears`() = runTest {
        val store = FilePcPairings(tmp.root)
        assertNull(store.current())

        val pairing = PcPairing("192.168.1.42", 8391, "abc123")
        store.save(pairing)
        assertEquals(pairing, FilePcPairings(tmp.root).current())

        store.clear()
        assertNull(FilePcPairings(tmp.root).current())
    }
}
