package app.memoria

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IndexLogicTest {

    @Test fun etaIsNullBeforeOnePhotoIsDone() {
        assertNull(etaMillis(started = 0L, done = 0, total = 100))
        assertNull(etaMillis(started = 1L, done = 0, total = 100))
    }

    @Test fun etaIsNullWhenEverythingIsDone() {
        assertNull(etaMillis(started = 1L, done = 100, total = 100))
        assertNull(etaMillis(started = 1L, done = 120, total = 100))
    }

    @Test fun etaScalesElapsedTimeByPhotosLeft() {
        val started = System.currentTimeMillis() - 10_000
        // 10s for 5 photos -> 2s each -> 5 left -> ~10s
        val eta = etaMillis(started, done = 5, total = 10)!!
        assertTrue("eta was $eta", eta in 9_000..11_000)
    }

    @Test fun carryKeepsTheIdOfThePersonTheGroupMostlyHolds() {
        val old = listOf(PersonRow(7, mainFaceId = 1), PersonRow(9, mainFaceId = 5))
        val oldFaces = mapOf(7L to setOf(1L, 2L, 3L, 4L), 9L to setOf(5L, 6L))
        val taken = mutableSetOf<Long>()
        assertEquals(7L, carry(listOf(1, 2, 3, 10), old, oldFaces, taken)?.id) // 3 of 4 faces, new face 10 joined
        assertNull(carry(listOf(1, 2), old, oldFaces, taken))                   // 7 is taken by the first group
        assertNull(carry(listOf(1, 20), old, oldFaces, mutableSetOf()))         // 1 of 4 faces is not enough
    }

    @Test fun carryPrefersANamedPersonWhenTwoPeopleJoin() {
        val old = listOf(PersonRow(7, mainFaceId = 1), PersonRow(9, mainFaceId = 5, name = "A"))
        val oldFaces = mapOf(7L to setOf(1L, 2L, 3L, 4L), 9L to setOf(5L, 6L))
        assertEquals(9L, carry(listOf(1, 2, 3, 4, 5, 6), old, oldFaces, mutableSetOf())?.id)
    }

    @Test fun formatEta() {
        assertEquals("1s left", formatEta(0L))       // never "0s left"
        assertEquals("3s left", formatEta(3_000L))
        assertEquals("59s left", formatEta(59_000L))
        assertEquals("1m left", formatEta(60_000L))
        assertEquals("59m left", formatEta(3_599_000L))
        assertEquals("1h 0m left", formatEta(3_600_000L))
        assertEquals("1h 30m left", formatEta(5_400_000L))
    }
}
