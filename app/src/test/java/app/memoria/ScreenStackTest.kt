package app.memoria

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** The back stack's saved form. Where Back goes is checked on the real app in `NavigationTest`. */
class ScreenStackTest {

    // Android saves the stack the same way when it stops the app; a screen that can't be written crashes that save
    @Test fun everyScreen_survivesASaveAndRestore() {
        val stack = listOf(
            Screen.Settings(atUpdates = true), Screen.Licenses, Screen.Skip, Screen.Dev, Screen.Hidden(3), Screen.People(5, direct = true), Screen.Recognize(5),
            Screen.Favorites, Screen.Albums(naming = "Trip"), Screen.NewAlbum("Trip"), Screen.Album(7), Screen.Places, Screen.Place(9),
            Screen.Videos, Screen.Search, Screen.Trash,
        )
        val bytes = ByteArrayOutputStream().also { ObjectOutputStream(it).use { o -> o.writeObject(ArrayList(stack)) } }.toByteArray()
        assertEquals(stack, ObjectInputStream(ByteArrayInputStream(bytes)).readObject())
    }

    @Test fun screenKey_ignoresArguments_soPeopleKeepsItsStateWhenAPersonOpens() =
        assertEquals(screenKey(1, Screen.People()), screenKey(1, Screen.People(5)))

    @Test fun screenKey_differsByDepth() = assertNotEquals(screenKey(0, Screen.Album(7)), screenKey(1, Screen.Album(7)))

    @Test fun screenKey_differsByKind() = assertNotEquals(screenKey(1, Screen.Album(7)), screenKey(1, Screen.Place(7)))
}
