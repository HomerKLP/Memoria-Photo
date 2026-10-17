package app.memoria

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Video GPS string from MediaMetadataRetriever. */
class DetailsGpsTest {

    @Test fun parsesSignsAndAltitude() {
        assertEquals(37.4219 to -122.084, iso6709("+37.4219-122.0840/"))
        assertEquals(-33.8688 to 151.2093, iso6709("-33.8688+151.2093+010.000/"))
    }

    @Test fun nullOrJunkIsNull() {
        assertNull(iso6709(null))
        assertNull(iso6709(""))
        assertNull(iso6709("abc"))
    }
}
