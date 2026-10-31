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

    /** Stored rows for these vectors, scored by [photoTags] as the app does. */
    private fun rows(tags: List<Tag>, vararg v: Pair<Long, FloatArray>) = v.map { (id, vector) -> PhotoTagRow(id, 0, photoTags(tags, vector)) }

    @Test fun aboveTheCutoff_newestFirst_coverIsTheTopScore() {
        val vectors = rows(
            tags,
            1L to floatArrayOf(0.9f, 0.1f),
            2L to floatArrayOf(0.6f, 0.2f),
            3L to floatArrayOf(0.5f, 0.6f), // on A's cutoff: not in A
            4L to floatArrayOf(0.7f, 0.1f),
        )
        val a = categories(tags, vectors, listOf(tile(1, 100), tile(2, 300), tile(3, 200), tile(4, 400))).first { it.id == "a" }
        assertEquals(listOf(4L, 2L, 1L), a.tiles.map { it.id })
        assertEquals(1L, a.cover.id)
    }

    @Test fun mostPhotosFirst_emptyTagsAndGonePhotosLeftOut() {
        val vectors = rows(
            tags,
            1L to floatArrayOf(0f, 1f),
            2L to floatArrayOf(0f, 1f),
            3L to floatArrayOf(1f, 0f),
            9L to floatArrayOf(1f, 0f), // gone from the phone
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

    @Test fun petTag_fromBoxesWhenThereAreAny_coverIsTheSurestBox() {
        val pet = Tag("pet", "Pets", emptyList(), 0.5f, floatArrayOf(1f, 0f))
        val vectors = rows(listOf(pet), 1L to floatArrayOf(0.9f, 0f), 2L to floatArrayOf(0f, 0f))
        val tiles = listOf(tile(1, 100), tile(2, 200), tile(3, 300))
        assertEquals(listOf(1L), categories(listOf(pet), vectors, tiles).single().tiles.map { it.id }) // no boxes: the tagger
        val boxed = categories(listOf(pet), vectors, tiles, mapOf(2L to 0.9f, 3L to 0.6f, 9L to 0.7f)).single() // 9 is gone from the phone
        assertEquals(listOf(3L, 2L), boxed.tiles.map { it.id })
        assertEquals(2L, boxed.cover.id)
    }
}
