package app.memoria.baselineprofile

import androidx.benchmark.macro.MacrobenchmarkScope

// The app asks for these on launch; with none granted the dialog pops on every cold start of a
// benchmark run. Granting them as shell (idempotent) keeps the runs silent and uninterrupted.
private val PERMISSIONS = listOf(
    "android.permission.READ_MEDIA_IMAGES",
    "android.permission.READ_MEDIA_VIDEO",
    "android.permission.ACCESS_MEDIA_LOCATION",
    "android.permission.POST_NOTIFICATIONS",
)

/** Grant the app's runtime permissions before measuring, so no permission dialog appears. */
fun MacrobenchmarkScope.grantPermissions() {
    for (p in PERMISSIONS) device.executeShellCommand("pm grant $packageName $p")
}
