plugins {
    id("com.android.application") version "9.4.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
    id("com.google.devtools.ksp") version "2.3.12" apply false
    id("com.android.compose.screenshot") version "0.0.1-alpha16" apply false
    id("androidx.baselineprofile") version "1.5.0" apply false
}

// A device test uninstalls the app afterwards, which wipes its DB and models, so it runs on an emulator only.
subprojects {
    tasks.matching { it.name.startsWith("connected") && it.name.endsWith("AndroidTest") }.configureEach {
        doFirst {
            check(System.getenv("ANDROID_SERIAL").orEmpty().startsWith("emulator-")) {
                "Device tests wipe the app's data: run them on an emulator with ANDROID_SERIAL=emulator-5554 (make layout does)"
            }
        }
    }
}
