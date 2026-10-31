import com.android.tools.profgen.ArtProfile
import com.android.tools.profgen.ArtProfileSerializer
import com.android.tools.profgen.MetadataVersion

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("com.android.compose.screenshot")
    id("androidx.baselineprofile")
}

android {
    namespace = "app.memoria"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.memoria"
        minSdk = 33
        targetSdk = 36
        versionCode = 6
        versionName = "0.5.0"
        manifestPlaceholders["appName"] = "Memoria"
        ndk { abiFilters += "arm64-v8a" } // Android 13+ phones and the Mac emulator are arm64
    }
    buildTypes {
        // its own package, so debug and release sit side by side on the phone, each with its own data
        debug {
            applicationIdSuffix = ".debug"
            manifestPlaceholders["appName"] = "Memoria Debug"
        }
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
        // NPU libs for Snapdragon 8 Gen 1 to 8 Elite Gen 5 (HTP V69-V81); V68 has no fp16, so it runs on the CPU
        jniLibs.excludes += listOf("**/libQnnDsp*.so", "**/libQnnGpu.so", "**/libQnnHtpV68*.so")
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("sh.calvin.reorderable:reorderable:3.1.0") // drag to reorder named people
    implementation("com.google.ai.edge.litert:litert:2.3.0")
    implementation("com.google.ai.edge.litert:litert-gpu:2.3.0")
    implementation("com.google.ai.edge.litert:litert-npu-runtime-qualcomm:2.3.0")
    implementation("com.qualcomm.qti:qnn-runtime:2.50.0") // LiteRT 2.3.0 is built on QNN 2.50; on an older QNN it silently runs on the CPU
    implementation("androidx.room:room-runtime:2.8.5")
    implementation("androidx.room:room-ktx:2.8.5")
    ksp("androidx.room:room-compiler:2.8.5")
    implementation("androidx.work:work-runtime:2.12.0")
    // Installs baseline profiles (ours and the ones Compose/AndroidX ship) on first run
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    // the Macrobenchmark module (:baselineprofile) generates our profile
    baselineProfile(project(":baselineprofile"))
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

// Write the generated profile into app/src/main/baseline-prof.txt, so it ships in the APK.
baselineProfile {
    saveInSrc = true
}

// Android 15+ compiles assets/art-profile/baseline.prof at install, also outside Play; assets/dexopt waits for the idle job (bugs.md).
abstract class ArtProfileTask : DefaultTask() {
    @get:InputFile abstract val profile: RegularFileProperty
    @get:InputFile abstract val metadata: RegularFileProperty
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction fun write() {
        // AGP's profile is the old format plus metadata, for profileinstaller; ART wants it in its own (0.1.5)
        val prof = profile.get().asFile.inputStream().use { ArtProfile(it) }!!
        val meta = metadata.get().asFile.inputStream().use { ArtProfile(it) }!!
        val out = outputDir.file("art-profile/baseline.prof").get().asFile.apply { parentFile.mkdirs() }
        out.outputStream().use { prof.addMetadata(meta, MetadataVersion.V_002).save(it, ArtProfileSerializer.V0_1_5_S) }
    }
}

androidComponents {
    onVariants(selector().withBuildType("release")) { v ->
        val cap = v.name.replaceFirstChar { it.uppercase() }
        val dir = layout.buildDirectory.dir("intermediates")
        val task = tasks.register<ArtProfileTask>("artProfile$cap") {
            dependsOn("compile${cap}ArtProfile")
            profile.set(dir.map { it.file("binary_art_profile/${v.name}/compile${cap}ArtProfile/baseline.prof") })
            metadata.set(dir.map { it.file("binary_art_profile_metadata/${v.name}/compile${cap}ArtProfile/baseline.profm") })
        }
        v.sources.assets?.addGeneratedSourceDirectory(task, ArtProfileTask::outputDir)
    }
}
