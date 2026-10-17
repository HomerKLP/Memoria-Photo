package app.memoria

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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
            assertEquals(1L, dao.faceVecs().single().personId)
            assertTrue(dao.answers().single().yes)
            assertEquals(12L, dao.merges().single().face)
        }
        db.close()
    }
}
