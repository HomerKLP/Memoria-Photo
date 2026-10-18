package app.memoria.baselineprofile

import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.benchmark.macro.StartupMode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Scroll smoothness on the Photos grid — the "it stutters" screen. Janky frames %, from FrameTimingMetric.
 * Warm start, 5 runs. Numbers only mean something on a release build on the phone.
 */
@RunWith(AndroidJUnit4::class)
class ScrollBenchmarks {

    @get:Rule val rule = MacrobenchmarkRule()

    @Test
    fun scrollPhotos() = rule.measureRepeated(
        packageName = "app.memoria",
        metrics = listOf(FrameTimingMetric()),
        iterations = 3,
        startupMode = StartupMode.WARM,
        setupBlock = { grantPermissions() },
    ) {
        startActivityAndWait()
        device.findObject(By.text("Photos"))?.click() // the app opens on Photos; harmless if already there
        device.waitForIdle()
        val cx = device.displayWidth / 2
        repeat(4) { // 4 swipes up = one fling down the timeline
            device.swipe(cx, device.displayHeight * 3 / 4, cx, device.displayHeight / 4, 10)
            device.waitForIdle()
        }
    }
}
