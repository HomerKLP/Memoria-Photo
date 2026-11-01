package app.memoria

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The user's names, hidden people, answers and merges must survive every DB version bump. v7 is the first saved schema. */
class MigrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    @get:Rule val helper = MigrationTestHelper(instrumentation, Db::class.java)

    @Test fun userDataSurvivesUpgrade() {
        helper.createDatabase("migration-test", 7).apply {
            execSQL("INSERT INTO photo (id, path, kind) VALUES (1, 'DCIM/Camera/a.jpg', 'camera')")
            execSQL("INSERT INTO face (id, photoId, box, score, landmarks, vector, quality, thumb, personId, notFace) VALUES (10, 1, x'', 0.9, x'', x'', 0.9, x'', 1, 0)")
            execSQL("INSERT INTO person (id, mainFaceId, name, hidden) VALUES (1, 10, 'Dana', 1)")
            execSQL("INSERT INTO answer (faceId, personFace, yes) VALUES (11, 10, 1)")
            execSQL("INSERT INTO merged (face, personFace) VALUES (12, 10)")
            close()
        }
        // the app's migrations, no wipe fallback: a missing migration fails here
        val db = Room.databaseBuilder(instrumentation.targetContext, Db::class.java, "migration-test").addMigrations(*Db.MIGRATIONS).build()
        val dao = db.index()
        runBlocking {
            val person = dao.people().single()
            assertEquals("Dana", person.name)
            assertTrue(person.hidden)
            assertNull(person.coverFaceId)
            assertNull(person.pos) // v11: never moved
            assertEquals(1L, dao.faceVecs().single().personId)
            assertTrue(dao.answers().single().yes)
            assertEquals(12L, dao.merges().single().face)
            assertEquals(listOf(1L), dao.unplaced()) // v9: GPS not read yet, the next run does it
            assertEquals(emptyList<Long>(), dao.taggedIds(Tagger.VERSION)) // v12: no vectors yet, the next run tags every photo
            assertEquals(emptyList<Long>(), dao.boxedIds(ObjectDetector.VERSION)) // v13: no boxes yet, the next run boxes every photo
            assertEquals(false, dao.faceVecs().single().pet) // v14: old faces are faces, not pets
        }
        db.close()
    }

    /** The columns the v7 seed can't fill: covers, order, pets, vectors and tags. */
    @Test fun v15DataSurvivesUpgrade() {
        helper.createDatabase("migration-test-15", 15).apply {
            execSQL("INSERT INTO photo (id, path, kind, objects) VALUES (1, 'DCIM/Camera/a.jpg', 'camera', ${ObjectDetector.VERSION})")
            execSQL("INSERT INTO face (id, photoId, box, score, landmarks, vector, quality, thumb, personId, notFace, nearSure, pet) VALUES (10, 1, x'', 0.9, x'', x'', 0.9, x'', 1, 0, 0, 1)")
            execSQL("INSERT INTO person (id, mainFaceId, name, hidden, coverFaceId, pos) VALUES (1, 10, 'Barsik', 0, 10, 2)")
            execSQL("INSERT INTO photo_vector (photoId, model, vector) VALUES (1, ${Tagger.VERSION}, x'')")
            execSQL("INSERT INTO photo_tag (photoId, stamp, tags) VALUES (1, 7, '3 0.5')")
            close()
        }
        val db = Room.databaseBuilder(instrumentation.targetContext, Db::class.java, "migration-test-15").addMigrations(*Db.MIGRATIONS).build()
        val dao = db.index()
        runBlocking {
            val person = dao.people().single()
            assertEquals(10L, person.coverFaceId)
            assertEquals(2, person.pos)
            assertTrue(dao.faceVecs().single().pet)
            assertEquals(listOf(1L), dao.taggedIds(Tagger.VERSION)) // v16: the model index
            assertEquals(listOf(1L), dao.boxedIds(ObjectDetector.VERSION))
            assertEquals("3 0.5", dao.photoTags(7).single().tags)
        }
        db.close()
    }
}
