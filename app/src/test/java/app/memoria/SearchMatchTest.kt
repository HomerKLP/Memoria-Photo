package app.memoria

import org.junit.Assert.assertEquals
import org.junit.Test

/** Search hits match the start of a word, any case. */
class SearchMatchTest {

    @Test fun matchesTheStartOfAWord() {
        assertEquals(0, wordStart("d", "Downloads"))
        assertEquals(-1, wordStart("d", "Andy"))
        assertEquals(9, wordStart("2025", "December 2025"))
        assertEquals(0, wordStart(" dec ", "December 2025"))
        assertEquals(5, wordStart("2012", "Фото 2012 г."))
        assertEquals(0, wordStart("фо", "Фото 2012 г."))
        assertEquals(4, wordStart("chat", "Tg: chats"))
        assertEquals(-1, wordStart("danas", "Dana"))
        assertEquals(-1, wordStart("", "Dana"))
    }
}
