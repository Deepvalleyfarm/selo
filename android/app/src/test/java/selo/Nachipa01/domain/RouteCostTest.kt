package selo.Nachipa01.domain

import org.junit.Assert.*
import org.junit.Test
import selo.Nachipa01.data.maps.PlacesCache
import selo.Nachipa01.domain.route.*
import selo.Nachipa01.ui.screens.auth.extractOtp

/** Google Maps cost rules on the phone, Beem OTP auto-read, geohash parity with the server. */
class RouteCostTest {
    @Test fun autocompleteNeedsThreeCharacters() {
        assertFalse(PlacesCache.shouldSearch("Ka", 3)); assertFalse(PlacesCache.shouldSearch("  ab ", 3)); assertTrue(PlacesCache.shouldSearch("Kab", 3))
        assertEquals(PlacesCache.key(" Cairo   Rd ", "lusaka"), PlacesCache.key("cairo rd", "lusaka"))
    }

    @Test fun reroutesOnlyAfterRepeatedOffRouteFixesAndRespectsLimits() {
        val p = ReroutePolicy(minIntervalMs = 45_000, maxPerHour = 2, fixes = 3)
        var t = 0L
        p.routed(t)
        assertFalse(p.onFix(true, 50_000)); assertFalse(p.onFix(true, 51_000)); assertTrue(p.onFix(true, 52_000)) // 3rd fix, 52 s after last route
        p.routed(52_000)
        assertFalse(p.onFix(true, 60_000)); assertFalse(p.onFix(false, 61_000)); assertFalse(p.onFix(true, 62_000)) // jitter resets the counter
        repeat(3) { t = 120_000L + it * 1000; p.onFix(true, t) }
        assertFalse(p.onFix(true, 130_000)) // already 2 routes this hour
    }

    @Test fun finishedStopsTrimTheRouteInsteadOfANewRequest() {
        val a = LatLng(-15.41, 28.28); val b = LatLng(-15.42, 28.29); val c = LatLng(-15.43, 28.30)
        val d = Directions(listOf(NavStep("Go", "straight", 1000, 120, listOf(a, b), 0), NavStep("Arrive", "arrive", 0, 0, emptyList(), 0),
            NavStep("Go", "straight", 1500, 180, listOf(b, c), 1), NavStep("Arrive", "arrive", 0, 0, emptyList(), 1)), 2500, 300)
        val trimmed = d.dropLegs(1)
        assertEquals(1500, trimmed.distanceM); assertEquals(180, trimmed.durationS); assertTrue(trimmed.steps.all { it.legIndex == 0 })
        assertTrue(isSuffix(listOf("A:PICKUP", "A:DROPOFF"), listOf("A:DROPOFF"))); assertFalse(isSuffix(listOf("A:PICKUP"), listOf("B:PICKUP", "A:PICKUP")))
    }

    @Test fun liveEtaShrinksAlongThePath() {
        val path = listOf(LatLng(-15.40, 28.28), LatLng(-15.40, 28.30))
        assertEquals(20, LiveEta.minutes(path, LatLng(-15.40, 28.28), 20)); assertEquals(10, LiveEta.minutes(path, LatLng(-15.40, 28.29), 20))
        assertEquals(20, LiveEta.minutes(emptyList(), null, 20))
    }

    @Test fun geohashMatchesServer() { assertEquals("u4pruydqqvj", GeoHash.encode(57.64911, 10.40744, 11)); assertEquals(9, GeoHash.cellsAround(-15.41, 28.28).size) }

    @Test fun readsTheBeemPinFromTheSms() {
        assertEquals("241663", extractOtp("Your Selonachipa code is 241663. FA+9qCX9VSu"))
        assertNull(extractOtp("Call 0977123456 for help")) // 10-digit numbers are not codes
        assertNull(extractOtp(null))
    }
}
