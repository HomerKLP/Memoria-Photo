package app.memoria.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Generates app/src/main/baseline-prof.txt: starts the app and walks the screens people open first,
 * so the release build does not run them cold. Run `./gradlew :app:generateBaselineProfile` on a device.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule val rule = BaselineProfileRule()

    @Test
    fun generate() = rule.collect(
        packageName = "app.memoria",
        maxIterations = 6, // fewer launches: the phone is not a throwaway test device
        stableIterations = 2,
    ) {
        // grant first: the media permission dialog otherwise pops on every cold start and eats the swipes
        grantPermissions()
        pressHome()
        startActivityAndWait()

        // Photos tab: the wordmark, the grid and its thumbnails
        device.wait(Until.hasObject(By.text("Memoria Photo")), 5_000)
        device.swipe(device.displayWidth / 2, device.displayHeight * 3 / 4, device.displayWidth / 2, device.displayHeight / 4, 8)
        device.waitForIdle()

        // Library tab: the cards, the people row and the album row
        device.findObject(By.text("Library"))?.click()
        device.wait(Until.hasObject(By.text("Photos")), 5_000)
        device.waitForIdle()
        device.swipe(device.displayWidth / 2, device.displayHeight * 3 / 4, device.displayWidth / 2, device.displayHeight / 4, 8)
        device.waitForIdle()

        // back to Photos, the tab switch people do most
        device.findObject(By.text("Photos"))?.click()
        device.waitForIdle()
    }
}
