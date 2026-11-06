package app.memoria

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/**
 * Video faces and pets (ROADMAP Phase 13): they only join people and pets built from photos, never start one,
 * and never show on People. One video counts as one video, not a photo.
 */
class VideoGroupingTest {

    private val box = floatArrayOf(0f, 0f, 50f, 50f)

    /** A face whose vector is [d] cosine distance from the seeds' (axis 0), so the distances read exactly. */
    private fun face(id: Long, photoId: Long, quality: Float, kind: PhotoKind, d: Float) =
        FaceVec(id, photoId, box, FloatArray(512).also { it[0] = 1f - d; it[1] = sqrt(1f - it[0] * it[0]) }, quality, kind, null)

    private fun pet(id: Long, photoId: Long, x: Float, y: Float, kind: PhotoKind = PhotoKind.camera) =
        FaceVec(id, photoId, box, floatArrayOf(x, y), 0.8f, kind, null, pet = true)

    /** Six camera faces on axis 0: a person of 6 photos, average vector e0. */
    private val seeds = (1L..6L).map { face(it, it, 0.9f, PhotoKind.camera, 0f) }

    private fun joined(faces: List<FaceVec>, answers: List<AnswerRow> = emptyList()) =
        groupFaces(seeds + faces, answers).single().faces.map { it.id }.toSet()

    @Test fun aVideoFaceNeverStartsAPerson() {
        val videos = (1L..6L).map { face(it, it, 0.9f, PhotoKind.video, 0f) }
        assertTrue(groupFaces(videos).isEmpty()) // no camera photo to start from
        // even "Who is this? > New person" on a video face starts nothing
        assertTrue(groupFaces(videos, listOf(AnswerRow(1, 1, true))).isEmpty())
    }

    @Test fun aBlurryVideoTrackJoinsOnlyWithin062() {
        // 0.60 from the person: blurry but close enough
        assertTrue(joined(listOf(face(7, 7, 0.3f, PhotoKind.video, 0.60f))).contains(7L))
        // 0.65: under the face cut (0.68) but over the blurry bar
        assertTrue(joined(listOf(face(8, 8, 0.3f, PhotoKind.video, 0.65f))).none { it == 8L })
    }

    @Test fun aSharpVideoFaceJoinsWithin068() {
        assertTrue(joined(listOf(face(7, 7, 0.8f, PhotoKind.video, 0.65f))).contains(7L))
    }

    @Test fun aVideoCountsAsAVideoNotAPhoto() {
        val p = groupFaces(seeds + listOf(face(7, 7, 0.8f, PhotoKind.video, 0.5f))).single()
        assertTrue(p.faces.any { it.id == 7L }) // it joined
        assertEquals(6, p.photos) // 6 photos and 1 video: the video is not a photo
        assertEquals(1, p.videos)
        // the video face never shows: not in the faces row, the Unsure card or the face count
        assertEquals(6, p.shownFaces.size)
        assertTrue(p.shownFaces.none { it.kind == PhotoKind.video })
    }

    @Test fun aVideoFaceIsNeverTheMainFaceOrCover() {
        val p = groupFaces(seeds + listOf(face(7, 7, 0.99f, PhotoKind.video, 0.5f))).single()
        assertEquals(PhotoKind.camera, p.main.kind)
        assertEquals(PhotoKind.camera, p.cover.kind)
    }

    @Test fun aVideoPetNeverStartsAPet() {
        assertEquals(1, groupPets((1L..6L).map { pet(it, it, 1f, 0f) }).size) // 6 photos of one cat: a pet
        val videoCats = (11L..16L).map { pet(it, it, 0f, 1f, PhotoKind.video) }
        assertTrue(groupPets(videoCats).isEmpty()) // videos never group into a pet of their own
        assertTrue(groupPets(videoCats, listOf(AnswerRow(11, 11, true))).isEmpty()) // nor a New pet
    }

    @Test fun aVideoPetJoinsAShownPet() {
        val cat = (1L..6L).map { pet(it, it, 1f, 0f) }
        val videoCat = pet(11, 11, 0.8f, 0.6f, PhotoKind.video) // 0.2 from the cat
        val pets = groupPets(cat + listOf(videoCat))
        assertEquals(1, pets.size)
        assertTrue(pets.single().faces.any { it.id == 11L })
        assertEquals(6, pets.single().photos) // the video is not one of the pet's pictures
        assertEquals(1, pets.single().videos)
        assertTrue(pets.single().shownFaces.none { it.kind == PhotoKind.video })
    }
}
