package app.memoria

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tapping the Photos tab: a jump to the top is cheap, a long animation is not. */
class ScrollToTopTest {

    @Test fun atTheTopStillAnimates() {
        // index 0 is already the top; the animation is a no-op there
        assertFalse(jumpToTop(firstVisibleItemIndex = 0, visibleItemCount = 24))
    }

    @Test fun aShortHopAnimates() {
        assertFalse(jumpToTop(firstVisibleItemIndex = 24, visibleItemCount = 24))
    }

    @Test fun pastAScreenJumps() {
        assertTrue(jumpToTop(firstVisibleItemIndex = 25, visibleItemCount = 24))
        assertTrue(jumpToTop(firstVisibleItemIndex = 600, visibleItemCount = 24))
    }

    @Test fun anUnlaidGridJumps() {
        // visibleItemsInfo is empty before the first layout; scrollToItem is a safe no-op then
        assertTrue(jumpToTop(firstVisibleItemIndex = 0, visibleItemCount = 0))
    }
}
