package app.memoria.baselineprofile

import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Startup numbers for the Phase 10 goal ("a number per screen"). Run on a release build on a device:
 * `./gradlew :app:generateBaselineProfile` writes the profile; these are measured with
 * `./gradlew :baselineprofile:connectedBenchmarkAndroidTest` (uninstalls the app after: back up the DB first; `make perf` is the safe gauge).
 */
@RunWith(AndroidJUnit4::class)
class StartupBenchmarks {

    @get:Rule val rule = MacrobenchmarkRule()

    @Test
    fun startupCold() = rule.measureRepeated(
        packageName = "app.memoria",
        metrics = listOf(StartupTimingMetric()),
        iterations = 3,
        startupMode = StartupMode.COLD,
        setupBlock = { grantPermissions() },
    ) {
        pressHome()
        startActivityAndWait()
    }

    @Test
    fun startupWarm() = rule.measureRepeated(
        packageName = "app.memoria",
        metrics = listOf(StartupTimingMetric()),
        iterations = 3,
        startupMode = StartupMode.WARM,
        setupBlock = { grantPermissions() },
    ) {
        startActivityAndWait()
    }
}
