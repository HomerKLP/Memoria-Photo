package app.memoria

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RegroupTest {

    @Test fun nothingChanged_writesNothing() {
        val w = faceWrites(mapOf(1L to 7L, 2L to 7L), setOf(2L), mapOf(1L to 7L, 2L to 7L), setOf(2L))
        assertTrue(w.moved.isEmpty())
        assertTrue(w.left.isEmpty())
        assertTrue(w.vouched.isEmpty())
        assertTrue(w.unvouched.isEmpty())
    }

    @Test fun aFaceThatJoinsAPerson_isWrittenOnlyForItsNewPerson() {
        val w = faceWrites(mapOf(1L to 7L, 2L to null), emptySet(), mapOf(1L to 7L, 2L to 7L), emptySet())
        assertEquals(mapOf(7L to listOf(2L)), w.moved)
        assertTrue(w.left.isEmpty())
    }

    @Test fun aFaceThatLeavesEveryPerson_isUnlinked() {
        val w = faceWrites(mapOf(1L to 7L, 2L to 7L), emptySet(), mapOf(1L to 7L), emptySet())
        assertEquals(listOf(2L), w.left)
        assertTrue(w.moved.isEmpty())
    }

    /** The one the old clear-every-row step also did: a stale flag must go back to 0, or the face shows as sure forever. */
    @Test fun aStaleNearSure_isCleared_andANewOneIsSet() {
        val w = faceWrites(mapOf(1L to 7L, 2L to 7L), setOf(2L), mapOf(1L to 7L, 2L to 7L), setOf(1L))
        assertEquals(listOf(1L), w.vouched)
        assertEquals(listOf(2L), w.unvouched)
        assertTrue(w.moved.isEmpty())
    }

    /** A face that was loose and is still loose is not written, even though it has no person. */
    @Test fun aLooseFaceThatStaysLoose_isNotWritten() {
        val w = faceWrites(mapOf(3L to null), emptySet(), emptyMap(), emptySet())
        assertTrue(w.moved.isEmpty())
        assertTrue(w.left.isEmpty())
    }
}
