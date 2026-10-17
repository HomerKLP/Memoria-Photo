package app.memoria

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The foreground auto-index decision and its change-burst debounce, no phone needed. */
class AutoIndexTest {

    @Test fun burstIsNotDueBeforeAnyChange() {
        val burst = AutoIndex.Burst(quietMillis = 3000)
        assertFalse(burst.due(nowMillis = 10_000))
    }

    @Test fun burstIsNotDueBeforeTheQuietWindow() {
        val burst = AutoIndex.Burst(quietMillis = 3000)
        burst.change(nowMillis = 1000)
        assertFalse(burst.due(2000))
        assertFalse(burst.due(3999)) // 2999 ms later, still inside the window
    }

    @Test fun burstIsDueAfterTheQuietWindow() {
        val burst = AutoIndex.Burst(quietMillis = 3000)
        burst.change(nowMillis = 1000)
        assertTrue(burst.due(4000)) // exactly 3000 ms later
        assertTrue(burst.due(9000))
    }

    @Test fun anotherChangeRestartsTheQuietWindow() {
        val burst = AutoIndex.Burst(quietMillis = 3000)
        burst.change(nowMillis = 1000)
        burst.change(nowMillis = 3500) // a second photo of the same import
        assertFalse(burst.due(5000)) // 1500 ms after the last change
        assertTrue(burst.due(6500))
    }

    @Test fun takeDisarmsTheBurstSoItRunsOnce() {
        val burst = AutoIndex.Burst(quietMillis = 3000)
        burst.change(nowMillis = 1000)
        assertTrue(burst.due(4000))
        burst.take()
        assertFalse(burst.due(4000))
        assertFalse(burst.due(10_000))
        burst.change(nowMillis = 11_000) // a fresh import arms it again
        assertTrue(burst.due(14_000))
    }

    @Test fun shouldStartOnlyWhenEverythingLinesUp() {
        assertTrue(AutoIndex.shouldStart(hasPermission = true, modelsReady = true, indexBusy = false, paused = false, changedPhotos = 1))
    }

    @Test fun shouldNotStartWithoutPhotoPermission() {
        assertFalse(AutoIndex.shouldStart(hasPermission = false, modelsReady = true, indexBusy = false, paused = false, changedPhotos = 5))
    }

    @Test fun shouldNotStartWithoutModels() {
        assertFalse(AutoIndex.shouldStart(hasPermission = true, modelsReady = false, indexBusy = false, paused = false, changedPhotos = 5))
    }

    @Test fun shouldNotStartWhileARunIsGoingOrQueued() {
        assertFalse(AutoIndex.shouldStart(hasPermission = true, modelsReady = true, indexBusy = true, paused = false, changedPhotos = 5))
    }

    @Test fun shouldNotStartWhenTheUserPaused() {
        assertFalse(AutoIndex.shouldStart(hasPermission = true, modelsReady = true, indexBusy = false, paused = true, changedPhotos = 5))
    }

    @Test fun shouldNotStartWhenNothingIsNew() {
        // e.g. a favorite toggle touches MediaStore but adds no photo, so a run would only spin its wheels
        assertFalse(AutoIndex.shouldStart(hasPermission = true, modelsReady = true, indexBusy = false, paused = false, changedPhotos = 0))
    }
}
