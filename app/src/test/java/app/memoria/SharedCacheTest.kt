package app.memoria

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Library and Search show one snapshot: the value is reused only for the keys it was built for. A rename or an
 * index save bumps `peopleSaved`, and going back to a tab bumps the reload counter, so both screens build again
 * instead of showing the old copy.
 */
class SharedCacheTest {

    @Test fun aCopyBuiltForAnotherStampIsNotReused() {
        val values = mutableMapOf<String, Any?>("library" to "old")
        val stamps = mutableMapOf<String, List<Any?>>("library" to listOf(1, 0))

        assertSame("old", sharedHit(values, stamps, "library", listOf(1, 0))) // the same keys: shared
        assertNull(sharedHit(values, stamps, "library", listOf(2, 0))) // a person was saved: old copy
        assertNull(sharedHit(values, stamps, "library", listOf(1, 3))) // back to the tab: old copy
        assertNull(sharedHit(values, stamps, "search", listOf(1, 0))) // nothing built yet
    }

    @Test fun theSameStampLoadsOnceAndANewOneLoadsAgain() = runBlocking {
        var loads = 0
        val load = suspend { loads++; "data $loads" }

        assertEquals("data 1", sharedValue("test-shared-once", listOf(1), load))
        assertEquals("data 1", sharedValue("test-shared-once", listOf(1), load))
        assertEquals(1, loads)

        assertEquals("data 2", sharedValue("test-shared-once", listOf(2), load))
        assertEquals(2, loads)
    }
}
