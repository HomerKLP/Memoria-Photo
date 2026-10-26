package app.memoria

import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which photos a tag gets, its cover and the order. On a device: [Tile] needs a real Uri and `org.json` is a stub in unit tests. */
class CategoriesTest {

    private fun tile(id: Long, date: Long = 0L) = Tile(id, Uri.parse("content://media/external/images/media/$id"), date, null)

    // 2-number vectors: a photo at (x, y) scores x for A and y for B; nothing scores for C
    private val tags = listOf(
        Tag("a", "A", emptyList(), 0.5f, floatArrayOf(1f, 0f)),
        Tag("b", "B", listOf("bee"), 0.5f, floatArrayOf(0f, 1f)),
        Tag("c", "C", emptyList(), 0.5f, floatArrayOf(-1f, 0f)),
    )

    @Test fun aboveTheCutoff_newestFirst_coverIsTheTopScore() {
        val vectors = listOf(
            PhotoVectorRow(1, 1, floatArrayOf(0.9f, 0.1f)),
            PhotoVectorRow(2, 1, floatArrayOf(0.6f, 0.2f)),
            PhotoVectorRow(3, 1, floatArrayOf(0.5f, 0.6f)), // on A's cutoff: not in A
            PhotoVectorRow(4, 1, floatArrayOf(0.7f, 0.1f)),
        )
        val a = categories(tags, vectors, listOf(tile(1, 100), tile(2, 300), tile(3, 200), tile(4, 400))).first { it.id == "a" }
        assertEquals(listOf(4L, 2L, 1L), a.tiles.map { it.id })
        assertEquals(1L, a.cover.id)
    }

    @Test fun mostPhotosFirst_emptyTagsAndGonePhotosLeftOut() {
        val vectors = listOf(
            PhotoVectorRow(1, 1, floatArrayOf(0f, 1f)),
            PhotoVectorRow(2, 1, floatArrayOf(0f, 1f)),
            PhotoVectorRow(3, 1, floatArrayOf(1f, 0f)),
            PhotoVectorRow(9, 1, floatArrayOf(1f, 0f)), // gone from the phone
        )
        val found = categories(tags, vectors, listOf(tile(1), tile(2), tile(3)))
        assertEquals(listOf("b", "a"), found.map { it.id })
        assertEquals(listOf(3L), found[1].tiles.map { it.id })
        assertEquals(listOf("bee"), found[0].words)
    }

    @Test fun tagsFileForAnotherModel_givesNoTags() =
        assertTrue(Tag.parse("""{"model": ${Tagger.VERSION + 1}, "tags": [{"id": "a", "name": "A", "words": [], "cutoff": 0.1, "vector": [1.0]}]}""").isEmpty())

    @Test fun shippedTagsFile_loads() {
        val tags = Tag.load(InstrumentationRegistry.getInstrumentation().targetContext)
        assertTrue(tags.isNotEmpty())
        assertTrue(tags.all { it.vector.size == 768 })
    }
}
