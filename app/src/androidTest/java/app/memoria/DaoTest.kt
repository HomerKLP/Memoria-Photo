package app.memoria

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The join, group and EXISTS queries of [IndexDao] on an in-memory DB. */
class DaoTest {
    private lateinit var db: Db
    private lateinit var dao: IndexDao

    @Before fun open() {
        db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, Db::class.java).build()
        dao = db.index()
    }

    @After fun close() = db.close()

    @Test fun faceVecs_skipsNotFace_andFlagsAnswers() = runBlocking {
        dao.insert(
            PhotoRow(1, "DCIM/Camera/a.jpg", PhotoKind.camera),
            listOf(
                FaceRow(id = 1, photoId = 1, box = FloatArray(0), score = 0.9f, landmarks = FloatArray(0), vector = FloatArray(0), quality = 0.9f, thumb = ByteArray(0)),
                FaceRow(id = 2, photoId = 1, box = FloatArray(0), score = 0.9f, landmarks = FloatArray(0), vector = FloatArray(0), quality = 0.9f, thumb = ByteArray(0), notFace = true),
                FaceRow(id = 3, photoId = 1, box = FloatArray(0), score = 0.9f, landmarks = FloatArray(0), vector = FloatArray(0), quality = 0.9f, thumb = ByteArray(0)),
                FaceRow(id = 4, photoId = 1, box = FloatArray(0), score = 0.9f, landmarks = FloatArray(0), vector = FloatArray(0), quality = 0.9f, thumb = ByteArray(0)),
            ),
        )
        dao.answer(AnswerRow(faceId = 3, personFace = 1, yes = true))
        dao.answer(AnswerRow(faceId = 4, personFace = 1, yes = false))
        val faces = dao.faceVecs().sortedBy { it.id }
        assertEquals(listOf(1L, 3L, 4L), faces.map { it.id })
        assertEquals(listOf(PhotoKind.camera, PhotoKind.camera, PhotoKind.camera), faces.map { it.kind })
        assertEquals(listOf(false, true, false), faces.map { it.yes })
        assertEquals(listOf(false, false, true), faces.map { it.rejected })
    }

    @Test fun photoFaces_onlyThatPhoto_noNotFace() = runBlocking {
        dao.insert(
            PhotoRow(1, "DCIM/Camera/a.jpg", PhotoKind.camera),
            listOf(
                FaceRow(id = 1, photoId = 1, box = FloatArray(0), score = 0.9f, landmarks = FloatArray(0), vector = FloatArray(0), quality = 0.9f, thumb = ByteArray(0)),
                FaceRow(id = 2, photoId = 1, box = FloatArray(0), score = 0.9f, landmarks = FloatArray(0), vector = FloatArray(0), quality = 0.9f, thumb = ByteArray(0), notFace = true),
            ),
        )
        dao.insert(
            PhotoRow(2, "Download/b.jpg", PhotoKind.other),
            listOf(FaceRow(id = 3, photoId = 2, box = FloatArray(0), score = 0.9f, landmarks = FloatArray(0), vector = FloatArray(0), quality = 0.9f, thumb = ByteArray(0))),
        )
        assertEquals(listOf(1L), dao.photoFaces(1).map { it.id })
        assertEquals(listOf(PhotoKind.other), dao.photoFaces(2).map { it.kind })
    }

    @Test fun taggedFaces_onlyPeopleFaces_noNotFace() = runBlocking {
        dao.insert(
            PhotoRow(1, "DCIM/Camera/a.jpg", PhotoKind.camera),
            listOf(
                FaceRow(id = 1, photoId = 1, box = FloatArray(0), score = 0.9f, landmarks = FloatArray(0), vector = FloatArray(0), quality = 0.9f, thumb = ByteArray(0), personId = 7),
                FaceRow(id = 2, photoId = 1, box = FloatArray(0), score = 0.9f, landmarks = FloatArray(0), vector = FloatArray(0), quality = 0.9f, thumb = ByteArray(0)),
                FaceRow(id = 3, photoId = 1, box = FloatArray(0), score = 0.9f, landmarks = FloatArray(0), vector = FloatArray(0), quality = 0.9f, thumb = ByteArray(0), personId = 7, notFace = true),
            ),
        )
        assertEquals(listOf(1L), dao.taggedFaces().map { it.id })
    }

    @Test fun taggedIds_onlyThatModel_newVectorReplacesOld() = runBlocking {
        dao.insertVector(PhotoVectorRow(1, 1, FloatArray(768)))
        dao.insertVector(PhotoVectorRow(2, 0, FloatArray(768)))
        dao.insertVector(PhotoVectorRow(2, 1, FloatArray(0)))
        dao.insertVector(PhotoVectorRow(3, 0, FloatArray(768)))
        assertEquals(listOf(1L, 2L), dao.taggedIds(1).sorted())
        dao.deleteVectors(listOf(1))
        assertEquals(listOf(2L), dao.taggedIds(1))
    }

    @Test fun doneCount_needsThisModelsVector_onlyWhileTaggerIsOn() = runBlocking {
        dao.insert(PhotoRow(1, "DCIM/Camera/a.jpg", PhotoKind.camera), emptyList())
        dao.insert(PhotoRow(2, "DCIM/Camera/b.jpg", PhotoKind.camera), emptyList())
        dao.insert(PhotoRow(3, "Download/c.jpg", PhotoKind.other), emptyList())
        dao.insertVector(PhotoVectorRow(1, 1, FloatArray(768)))
        dao.insertVector(PhotoVectorRow(2, 0, FloatArray(768)))
        assertEquals(1, dao.doneCount(tagger = true, model = 1, objects = false, objectsModel = 1).first())
        assertEquals(3, dao.doneCount(tagger = false, model = 1, objects = false, objectsModel = 1).first())
    }

    @Test fun kinds_countsPhotosPerKind() = runBlocking {
        dao.insert(PhotoRow(1, "DCIM/Camera/a.jpg", PhotoKind.camera), emptyList())
        dao.insert(PhotoRow(2, "DCIM/Camera/b.jpg", PhotoKind.camera), emptyList())
        dao.insert(PhotoRow(3, "Download/c.jpg", PhotoKind.other), emptyList())
        assertEquals(mapOf(PhotoKind.camera to 2, PhotoKind.other to 1), dao.kinds().first())
    }

    @Test fun notFace_dropsThePerson_andLeavesTheCount() = runBlocking {
        dao.insert(
            PhotoRow(1, "DCIM/Camera/a.jpg", PhotoKind.camera),
            listOf(
                FaceRow(id = 1, photoId = 1, box = FloatArray(0), score = 0.9f, landmarks = FloatArray(0), vector = FloatArray(0), quality = 0.9f, thumb = ByteArray(0), personId = 7),
                FaceRow(id = 2, photoId = 1, box = FloatArray(0), score = 0.9f, landmarks = FloatArray(0), vector = FloatArray(0), quality = 0.9f, thumb = ByteArray(0), personId = 7),
            ),
        )
        dao.notFace(1)
        assertEquals(listOf(2L), dao.faceVecs().map { it.id })
        assertEquals(listOf(2L), dao.taggedFaces().map { it.id })
        assertEquals(1, dao.faceTotal())
        assertEquals(2, dao.faceCount().first()) // the row stays, so a re-index does not find it again
    }

    @Test fun movePerson_movesEveryFace_andOnlyThatPersons() = runBlocking {
        dao.insert(
            PhotoRow(1, "DCIM/Camera/a.jpg", PhotoKind.camera),
            listOf(
                FaceRow(id = 1, photoId = 1, box = FloatArray(0), score = 0.9f, landmarks = FloatArray(0), vector = FloatArray(0), quality = 0.9f, thumb = ByteArray(0), personId = 1),
                FaceRow(id = 2, photoId = 1, box = FloatArray(0), score = 0.9f, landmarks = FloatArray(0), vector = FloatArray(0), quality = 0.9f, thumb = ByteArray(0), personId = 1),
                FaceRow(id = 3, photoId = 1, box = FloatArray(0), score = 0.9f, landmarks = FloatArray(0), vector = FloatArray(0), quality = 0.9f, thumb = ByteArray(0), personId = 2),
                FaceRow(id = 4, photoId = 1, box = FloatArray(0), score = 0.9f, landmarks = FloatArray(0), vector = FloatArray(0), quality = 0.9f, thumb = ByteArray(0)),
            ),
        )
        dao.movePerson(from = 1, to = 2)
        assertEquals(mapOf(1L to 2L, 2L to 2L, 3L to 2L, 4L to null), dao.faceVecs().associate { it.id to it.personId })
    }

    @Test fun places_nullIsUnplaced_zeroIsNoGps_positiveIsPlaced() = runBlocking {
        dao.insert(PhotoRow(1, "DCIM/Camera/a.jpg", PhotoKind.camera), emptyList())
        dao.insert(PhotoRow(2, "DCIM/Camera/b.jpg", PhotoKind.camera, townId = 0), emptyList())
        dao.insert(PhotoRow(3, "DCIM/Camera/c.jpg", PhotoKind.camera, lat = 1.0, lon = 2.0, townId = 5, town = "Town"), emptyList())
        assertEquals(listOf(1L), dao.unplaced())
        assertEquals(listOf(3L), dao.placed().map { it.id })
        dao.setPlace(1, 3.0, 4.0, 6, "Other")
        assertTrue(dao.unplaced().isEmpty())
        assertEquals(setOf(1L, 3L), dao.placed().map { it.id }.toSet())
    }

    @Test fun vectors_onlyThisModel_noEmpty() = runBlocking {
        dao.insertVector(PhotoVectorRow(1, 1, floatArrayOf(0.6f, 0.8f)))
        dao.insertVector(PhotoVectorRow(2, 1, FloatArray(0))) // couldn't be read
        dao.insertVector(PhotoVectorRow(3, 2, floatArrayOf(1f, 0f))) // another model
        val rows = dao.vectors(1)
        assertEquals(listOf(1L), rows.map { it.photoId })
        assertEquals(listOf(0.6f, 0.8f), rows.single().vector.toList())
    }

    @Test fun answer_sameFaceAndPersonReplaced_dropYesKeepsNo() = runBlocking {
        dao.answer(AnswerRow(faceId = 5, personFace = 1, yes = false))
        dao.answer(AnswerRow(faceId = 5, personFace = 1, yes = true))
        assertTrue(dao.answers().single().yes)
        dao.answer(AnswerRow(faceId = 6, personFace = 1, yes = false))
        dao.dropYes(5)
        dao.dropYes(6)
        val left = dao.answers().single()
        assertEquals(6L, left.faceId)
        assertFalse(left.yes)
    }

    @Test fun boxes_boxedByModel_petPhotosFromCatAndDog05() = runBlocking {
        dao.insert(PhotoRow(1, "DCIM/Camera/a.jpg", PhotoKind.camera, objects = 1), emptyList())
        dao.insert(PhotoRow(2, "DCIM/Camera/b.jpg", PhotoKind.camera), emptyList())
        dao.insertBoxes(listOf(
            ObjectBoxRow(photoId = 1, cls = PERSON, score = 0.9f, box = FloatArray(4)),
            ObjectBoxRow(photoId = 1, cls = CAT, score = 0.6f, box = FloatArray(4)),
            ObjectBoxRow(photoId = 1, cls = DOG, score = 0.8f, box = FloatArray(4)),
            ObjectBoxRow(photoId = 2, cls = CAT, score = 0.4f, box = FloatArray(4)), // under 0.5: no pet photo
        ))
        assertEquals(listOf(1L), dao.boxedIds(1))
        assertEquals(1, dao.doneCount(tagger = false, model = 1, objects = true, objectsModel = 1).first())
        assertEquals(2, dao.doneCount(tagger = false, model = 1, objects = false, objectsModel = 1).first())
        dao.setBoxed(2, 1)
        assertEquals(listOf(1L, 2L), dao.boxedIds(1).sorted())
        assertEquals(mapOf(1L to 0.8f), dao.petPhotos()) // the surest box
        dao.deleteBoxes(listOf(1))
        assertEquals(emptyMap<Long, Float>(), dao.petPhotos())
    }

    @Test fun pets_areFacesButNotPhotoFaces() = runBlocking {
        dao.insert(
            PhotoRow(1, "DCIM/Camera/a.jpg", PhotoKind.camera),
            listOf(
                FaceRow(id = 1, photoId = 1, box = FloatArray(4), score = 0.9f, landmarks = FloatArray(0), vector = FloatArray(0), quality = 0.9f, thumb = ByteArray(0)),
                FaceRow(id = 2, photoId = 1, box = FloatArray(4), score = 0.8f, landmarks = FloatArray(0), vector = FloatArray(0), quality = 0.8f, thumb = ByteArray(0), pet = true),
            ),
        )
        assertEquals(listOf(false, true), dao.faceVecs().sortedBy { it.id }.map { it.pet })
        assertEquals(listOf(1L), dao.photoFaces(1).map { it.id }) // the person-box rule sees faces only
        assertEquals(listOf(1L, 2L), dao.viewerFaces(1).map { it.id }.sorted()) // the viewer shows pets too
        dao.deletePets(1)
        assertEquals(listOf(1L), dao.faceVecs().map { it.id })
    }
}
