package app.memoria

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * How a photo is filed for people grouping: screenshots, GIFs and tiny images skip; a camera EXIF
 * make means "camera" (can start a person); everything else is "other" (only joins a person).
 */
class IndexKindTest {

    private fun photo(path: String = "DCIM/Camera/IMG_1.jpg", mime: String = "image/jpeg", w: Int = 4000, h: Int = 3000) =
        MediaPhoto(1, path, mime, w, h)

    @Test fun screenshotFolderSkips() = assertEquals("skip", kindOf(photo(path = "Pictures/Screenshots/shot.png"), hasCameraMake = true))

    @Test fun gifSkips() = assertEquals("skip", kindOf(photo(mime = "image/gif"), hasCameraMake = true))

    @Test fun tinyImageSkips() = assertEquals("skip", kindOf(photo(w = 120, h = 90), hasCameraMake = true))

    @Test fun skipBeatsCamera() = assertEquals("skip", kindOf(photo(path = "Download/screenshot_1.jpg"), hasCameraMake = true))

    @Test fun cameraMakeIsCamera() = assertEquals("camera", kindOf(photo(), hasCameraMake = true))

    @Test fun noMakeIsOther() = assertEquals("other", kindOf(photo(), hasCameraMake = false))

    @Test fun justUnderTheTinyCutoffSkips() = assertEquals("skip", kindOf(photo(w = 4000, h = 199), hasCameraMake = true))

    @Test fun atTheTinyCutoffIsNotSkipped() = assertEquals("camera", kindOf(photo(w = 4000, h = 200), hasCameraMake = true))
}
