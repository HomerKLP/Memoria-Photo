// Macrobenchmark module: generates the app's baseline profile and measures startup on a device.
// Not a real app: it drives :app. The generated profile lands in app/src/main/baseline-prof.txt
// (the `baselineProfile { saveInSrc }` block lives in :app, the consumer side).
// Run: ./gradlew :app:generateBaselineProfile   (needs a device or emulator)
plugins {
    id("com.android.test")
    id("androidx.baselineprofile")
}

android {
    namespace = "app.memoria.baselineprofile"
    compileSdk = 37

    defaultConfig {
        minSdk = 33
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    targetProjectPath = ":app"
    // the benchmark process instruments itself, not :app (baseline profiles are collected, not run in the app)
    experimentalProperties["android.experimental.self-instrumenting"] = true
}

dependencies {
    implementation("androidx.test.ext:junit:1.3.0")
    implementation("androidx.test.espresso:espresso-core:3.7.0")
    implementation("androidx.test.uiautomator:uiautomator:2.3.0")
    implementation("androidx.benchmark:benchmark-macro-junit4:1.5.0")
}

baselineProfile {
    // allow the generator to run on an emulator too (numbers are relative, good for before/after)
    skipBenchmarksOnEmulator = false
    enableEmulatorDisplay = true
}
