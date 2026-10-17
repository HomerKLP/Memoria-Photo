package app.memoria

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The select state itself (`PhotoSelection`), which the drag-select and the day circle drive.
 * Instrumented only because `Tile` needs a real `Uri` to build; the class is otherwise pure.
 */
class PhotoSelectionTest {

    private fun tile(id: Long, favorite: Boolean = false) =
        Tile(id, Uri.EMPTY, 0L, null, favorite)

    @Test fun start_replacesTheSelection() {
        val s = PhotoSelection()
        s.start(tile(1))
        s.start(tile(2))
        assertEquals(setOf(2L), s.ids)
    }

    @Test fun toggle_addsThenRemoves() {
        val s = PhotoSelection()
        s.toggle(tile(1))
        s.toggle(tile(2))
        assertEquals(setOf(1L, 2L), s.ids)
        s.toggle(tile(1))
        assertEquals(setOf(2L), s.ids)
    }

    @Test fun add_ignoresWhatIsAlreadyPicked() {
        val s = PhotoSelection()
        s.add(tile(1))
        s.add(tile(1))
        assertEquals(setOf(1L), s.ids)
    }

    @Test fun addAll_and_remove() {
        val s = PhotoSelection()
        s.addAll((1L..4L).map { tile(it) })
        assertEquals(4, s.ids.size)
        s.remove(listOf(2L, 3L))
        assertEquals(setOf(1L, 4L), s.ids)
    }

    @Test fun allFavorite_needsEveryPickToBeAFavorite() {
        val s = PhotoSelection()
        assertFalse(s.allFavorite)
        s.addAll(listOf(tile(1, favorite = true), tile(2, favorite = true)))
        assertTrue(s.allFavorite)
        s.add(tile(3))
        assertFalse(s.allFavorite)
    }

    @Test fun active_and_clear() {
        val s = PhotoSelection()
        assertFalse(s.active)
        s.start(tile(1))
        assertTrue(s.active)
        s.clear()
        assertFalse(s.active)
        assertTrue(s.ids.isEmpty())
    }
}
