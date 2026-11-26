package selo.Nachipa01.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import selo.Nachipa01.data.maps.RecentPlaces
import selo.Nachipa01.data.model.Place

class RecentPlacesTest {
    private val places = listOf(
        Place("a", "Arcades Shopping Mall", "Great East Rd, Lusaka", -15.40, 28.32, landmark = "Stall 14"),
        Place("b", "Soweto Market", "Lumumba Rd, Lusaka", -15.42, 28.27),
        Place("c", "Manda Hill", "Great East Rd, Lusaka", -15.39, 28.30),
    )
    @Test fun matchesEveryTypedWord() {
        assertEquals(listOf("a", "c"), RecentPlaces.matches(places, "great east", 4).map { it.id })
        assertEquals(listOf("a"), RecentPlaces.matches(places, "arcades stall", 4).map { it.id })
        assertEquals(emptyList<String>(), RecentPlaces.matches(places, "s", 4).map { it.id })
    }
}
