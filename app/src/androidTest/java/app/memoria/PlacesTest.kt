package app.memoria

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Test

/** A town's cover: the photo the tagger finds nicest, else the newest. On a device: [Tile] needs a real Uri. */
class PlacesTest {

    private fun tile(id: Long, date: Long) = Tile(id, Uri.parse("content://media/external/images/media/$id"), date, null)
    private fun row(id: Long, town: Int = 7) = PhotoRow(id, "p$id", PhotoKind.camera, townId = town, town = "Town")

    private val rows = listOf(row(1), row(2), row(3))

    @Test fun scoredPhotoWins_overANewerUnscoredOne() {
        val tiles = listOf(tile(1, 100), tile(2, 300), tile(3, 200))
        assertEquals(1L, places(rows, tiles, mapOf(1L to 0.2f, 3L to 0.1f)).single().cover?.id)
    }

    @Test fun highestScoreWins_evenWhenItIsOldest() {
        val tiles = listOf(tile(1, 300), tile(2, 200), tile(3, 100))
        assertEquals(3L, places(rows, tiles, mapOf(1L to 0.05f, 2L to 0.1f, 3L to 0.4f)).single().cover?.id)
    }

    @Test fun noScores_coverIsTheNewest() {
        val tiles = listOf(tile(1, 100), tile(2, 300), tile(3, 200))
        assertEquals(2L, places(rows, tiles).single().cover?.id)
    }
}
