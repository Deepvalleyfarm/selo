package selo.Nachipa01.domain

import org.junit.Assert.*
import org.junit.Test
import selo.Nachipa01.domain.route.*

class RouteTest {
    private val start = LatLng(-15.4205, 28.3330)          // Kabulonga
    private val soweto = LatLng(-15.4235, 28.2733); private val woodlands = LatLng(-15.4300, 28.3280)
    private val manda = LatLng(-15.3980, 28.3060); private val olympia = LatLng(-15.3920, 28.3110)
    private fun p(o: String, at: LatLng, done: Boolean = false) = Stop(o, StopType.PICKUP, "P$o", "", at, done)
    private fun d(o: String, at: LatLng) = Stop(o, StopType.DROPOFF, "D$o", "", at)

    @Test fun everyPickupComesBeforeItsDropoff() {
        val r = RoutePlanner.plan(start, listOf(d("A", woodlands), p("A", soweto), d("B", olympia), p("B", manda)))
        assertTrue(RoutePlanner.feasible(r.stops))
        listOf("A", "B").forEach { o -> assertTrue(r.stops.indexOfFirst { it.orderId == o && it.type == StopType.PICKUP } < r.stops.indexOfFirst { it.orderId == o && it.type == StopType.DROPOFF }) }
        assertEquals(4, r.stops.size)
    }

    @Test fun combinedRouteIsShorterThanDoingOrdersOneByOne() {
        val stops = listOf(p("A", manda), d("A", olympia), p("B", soweto), d("B", woodlands))
        val r = RoutePlanner.plan(start, stops)
        val naive = RoutePlanner.lengthKm(start, stops)
        assertTrue("planned ${r.totalKm} vs naive $naive", r.totalKm <= naive + 1e-9)
        assertTrue(r.etaMin > 0)
    }

    @Test fun collectedOrdersOnlyNeedTheirDropoff() {
        val r = RoutePlanner.plan(start, listOf(p("A", soweto, done = true), d("A", woodlands), p("B", manda), d("B", olympia)))
        assertEquals(3, r.stops.size)
        assertEquals(StopType.DROPOFF, r.stops.first { it.orderId == "A" }.type)
        assertEquals("A", r.stops.first().orderId) // Woodlands is right next to the rider
    }

    @Test fun insertionCostIsSmallForAJobOnTheWay() {
        val current = listOf(p("A", manda), d("A", olympia))
        val onTheWay = RoutePlanner.insertionKm(start, current, p("B", LatLng(-15.405, 28.315)), d("B", LatLng(-15.395, 28.309)))
        val farAway = RoutePlanner.insertionKm(start, current, p("C", LatLng(-15.38, 28.25)), d("C", LatLng(-15.44, 28.35)))
        assertTrue(onTheWay < farAway)
    }

    @Test fun progressTracksStepsAndDetectsOffRoute() {
        val dir = NavigationEngine.straightLine(start, listOf(p("A", woodlands), d("A", soweto)), "Motorbike")
        assertEquals(2, dir.steps.size)
        val mid = Geo.lerp(start, woodlands, 0.5)
        val s = NavigationEngine.progress(dir, mid)
        assertEquals(0, s.stepIndex); assertFalse(s.offRoute)
        assertTrue(s.toManeuverM in 500..1500)
        assertTrue(s.remainingM > s.toManeuverM)
        val off = NavigationEngine.progress(dir, LatLng(mid.lat + 0.003, mid.lng))  // ~330 m away
        assertTrue(off.offRoute)
        val later = NavigationEngine.progress(dir, Geo.lerp(woodlands, soweto, 0.3), fromStep = 0)
        assertEquals(1, later.stepIndex); assertEquals(1, later.legIndex)
        // never rewinds
        assertEquals(1, NavigationEngine.progress(dir, mid, fromStep = 1).stepIndex)
    }

    @Test fun promptsFireOncePerDistance() {
        assertEquals(400, NavigationEngine.promptFor(380, emptySet()))
        assertEquals(150, NavigationEngine.promptFor(120, setOf(400)))
        assertNull(NavigationEngine.promptFor(500, emptySet()))
        val st = NavStep("Turn left onto Burma Rd", "turn-left", 300, 40, emptyList(), 0)
        assertEquals("In 150 metres, turn left onto Burma Rd", NavigationEngine.spoken(st, 152))
    }

    @Test fun decodesGooglePolyline() {
        val pts = NavigationEngine.decodePolyline("_p~iF~ps|U_ulLnnqC_mqNvxq`@")
        assertEquals(3, pts.size)
        assertEquals(38.5, pts[0].lat, 1e-5); assertEquals(-120.2, pts[0].lng, 1e-5); assertEquals(-126.453, pts[2].lng, 1e-5)
    }
}
