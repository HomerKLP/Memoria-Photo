plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("com.android.compose.screenshot")
}

android {
    namespace = "app.memoria"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.memoria"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
    }
    buildFeatures {
        compose = true
    }
    experimentalProperties["android.experimental.enableScreenshotTest"] = true
    testOptions {
        unitTests.isReturnDefaultValues = true // android.jar stubs (LruCache in Photos.kt) return defaults instead of throwing
    }
    // Room writes schemas/app.memoria.Db/<version>.json; MigrationTest builds old databases from them
    sourceSets.getByName("androidTest").assets.srcDir("$projectDir/schemas")
    packaging {
        jniLibs.useLegacyPackaging = true // QNN needs its .so files on disk
        // ponytail: only the Snapdragon 8 Elite NPU (HTP V79), add other versions for other phones
        jniLibs.excludes += listOf("**/libQnnDsp*.so", "**/libQnnGpu.so", "**/libQnnHtpV6*.so", "**/libQnnHtpV73*.so", "**/libQnnHtpV75*.so", "**/libQnnHtpV81*.so")
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("com.microsoft.onnxruntime:onnxruntime-android-qnn:1.29.0")
    implementation("androidx.room:room-runtime:2.8.5")
    implementation("androidx.room:room-ktx:2.8.5")
    ksp("androidx.room:room-compiler:2.8.5")
    implementation("androidx.work:work-runtime:2.12.0")
    screenshotTestImplementation("com.android.tools.screenshot:screenshot-validation-api:0.0.1-alpha16")
    screenshotTestImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-tooling") // Android Studio's preview pane needs it

    // pure logic, no device (./gradlew test)
    testImplementation("junit:junit:4.13.2")
    // Compose UI tests on a device or emulator (./gradlew connectedDebugAndroidTest)
    androidTestImplementation(platform("androidx.compose:compose-bom:2026.09.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    // 3.7.0: the version Compose pulls (3.5.0) crashes on API 37 (InputManager.getInstance is gone)
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.room:room-testing:2.8.5")
    // room-testing needs serialization 1.8.1+, and the test APK must use the app's version (1.7.3 from Room)
    constraints { implementation("org.jetbrains.kotlinx:kotlinx-serialization-core:1.8.1") }
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
