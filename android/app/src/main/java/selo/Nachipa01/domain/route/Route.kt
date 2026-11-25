package selo.Nachipa01.domain.route

import kotlin.math.*

/**
 * Multi-stop route planning and turn-by-turn progress for riders (pure Kotlin, unit tested).
 * A rider can carry several orders at once; every pickup must come before its own drop-off.
 */
data class LatLng(val lat: Double, val lng: Double)

enum class StopType { PICKUP, DROPOFF }

data class Stop(
    val orderId: String, val type: StopType, val label: String, val address: String, val at: LatLng?,
    val done: Boolean = false,
) { val id get() = "$orderId-${type.name[0]}" }

data class PlannedRoute(val stops: List<Stop>, val legKm: List<Double>, val totalKm: Double, val etaMin: Int)

object Geo {
    const val ROAD_FACTOR = 1.3 // straight line → typical Lusaka road distance
    fun km(a: LatLng, b: LatLng): Double {
        val r = 6371.0; val dLat = Math.toRadians(b.lat - a.lat); val dLng = Math.toRadians(b.lng - a.lng)
        val h = sin(dLat / 2).pow(2) + cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLng / 2).pow(2)
        return 2 * r * asin(sqrt(h))
    }
    fun m(a: LatLng, b: LatLng) = km(a, b) * 1000
    fun bearing(a: LatLng, b: LatLng): Double {
        val y = sin(Math.toRadians(b.lng - a.lng)) * cos(Math.toRadians(b.lat))
        val x = cos(Math.toRadians(a.lat)) * sin(Math.toRadians(b.lat)) - sin(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * cos(Math.toRadians(b.lng - a.lng))
        return (Math.toDegrees(atan2(y, x)) + 360) % 360
    }
    /** Point at [frac] of the way from a to b. */
    fun lerp(a: LatLng, b: LatLng, frac: Double) = LatLng(a.lat + (b.lat - a.lat) * frac, a.lng + (b.lng - a.lng) * frac)
}

object RoutePlanner {
    /** Average moving speed by vehicle, km/h, used for ETAs when the Directions service is unavailable. */
    fun speedKmh(vehicle: String) = when { vehicle.contains("bicycle", true) -> 14.0; vehicle.contains("foot", true) -> 5.0; else -> 26.0 }

    private fun legKm(a: LatLng?, b: LatLng?) = if (a == null || b == null) 0.0 else Geo.km(a, b) * Geo.ROAD_FACTOR

    /** Precedence: a drop-off is only allowed once its order's pickup is done or already earlier in [seq]. */
    fun feasible(seq: List<Stop>, all: List<Stop> = seq): Boolean {
        val pending = all.filter { it.type == StopType.PICKUP && !it.done }.map { it.orderId }.toSet()
        val picked = mutableSetOf<String>()
        seq.forEach { s ->
            if (s.type == StopType.PICKUP) picked += s.orderId
            else if (!s.done && s.orderId in pending && s.orderId !in picked) return false
        }
        return true
    }

    fun lengthKm(start: LatLng, seq: List<Stop>): Double {
        var cur: LatLng? = start; var t = 0.0
        seq.forEach { t += legKm(cur, it.at); cur = it.at ?: cur }
        return t
    }

    /**
     * Orders the remaining stops: greedy nearest feasible stop, then relocate and 2-opt moves that keep every pickup
     * ahead of its drop-off, until nothing shortens the route. Rider loads are small (≤ 4 orders → ≤ 8 stops),
     * so this reaches the optimum or very close to it in well under a millisecond.
     */
    fun plan(start: LatLng, stops: List<Stop>, vehicle: String = "Motorbike"): PlannedRoute {
        val todo = stops.filter { !it.done }
        // greedy
        val seq = mutableListOf<Stop>(); val left = todo.toMutableList(); var cur = start
        while (left.isNotEmpty()) {
            val next = left.filter { feasible(seq + it, todo) }.minByOrNull { if (it.at == null) Double.MAX_VALUE else Geo.km(cur, it.at) } ?: left.first()
            seq += next; left -= next; cur = next.at ?: cur
        }
        // improve
        var best = seq.toList(); var bestLen = lengthKm(start, best); var improved = true; var guard = 0
        while (improved && guard++ < 200) {
            improved = false
            loop@ for (i in best.indices) for (j in best.indices) {
                if (i == j) continue
                val moved = best.toMutableList().also { val s = it.removeAt(i); it.add(j, s) }
                val len = lengthKm(start, moved)
                if (len < bestLen - 1e-9 && feasible(moved)) { best = moved; bestLen = len; improved = true; break@loop }
            }
            if (!improved) loop2@ for (i in 0 until best.size - 1) for (j in i + 1 until best.size) {
                val rev = best.subList(0, i) + best.subList(i, j + 1).reversed() + best.subList(j + 1, best.size)
                val len = lengthKm(start, rev)
                if (len < bestLen - 1e-9 && feasible(rev)) { best = rev; bestLen = len; improved = true; break@loop2 }
            }
        }
        var c: LatLng? = start
        val legs = best.map { s -> legKm(c, s.at).also { c = s.at ?: c } }
        return PlannedRoute(best, legs, legs.sum(), (legs.sum() / speedKmh(vehicle) * 60 + best.size * 3).roundToInt())
    }

    /** Extra km a new order adds to the rider's current plan — shown on the dispatch card. */
    fun insertionKm(start: LatLng, current: List<Stop>, newPickup: Stop, newDrop: Stop, vehicle: String = "Motorbike"): Double {
        val before = plan(start, current, vehicle).totalKm
        val after = plan(start, current + newPickup + newDrop, vehicle).totalKm
        return max(0.0, after - before)
    }
}

/* ===================================================================== turn-by-turn */

/** One manoeuvre from the Directions service. [legIndex] says which stop it leads to. */
data class NavStep(
    val instruction: String, val maneuver: String, val distanceM: Int, val durationS: Int, val path: List<LatLng>, val legIndex: Int,
)
data class Directions(val steps: List<NavStep>, val distanceM: Int, val durationS: Int, val source: String = "google") {
    val path: List<LatLng> get() = steps.flatMap { it.path }
}

data class NavState(
    val stepIndex: Int, val toManeuverM: Int, val remainingM: Int, val remainingS: Int, val offRouteM: Int,
    val legIndex: Int, val snapped: LatLng,
) { val offRoute get() = offRouteM > NavigationEngine.OFF_ROUTE_M }

object NavigationEngine {
    const val OFF_ROUTE_M = 50
    const val ARRIVED_M = 40
    /** Voice prompt distances before each manoeuvre. */
    val PROMPTS_M = listOf(400, 150, 30)

    private fun pathLenM(p: List<LatLng>) = p.zipWithNext().sumOf { (a, b) -> Geo.m(a, b) }

    /** Projects [p] onto segment a→b. Returns (fraction along segment, distance from segment in metres). */
    fun project(p: LatLng, a: LatLng, b: LatLng): Pair<Double, Double> {
        val kx = cos(Math.toRadians(a.lat)) * 111_320.0; val ky = 110_540.0
        val ax = 0.0; val ay = 0.0
        val bx = (b.lng - a.lng) * kx; val by = (b.lat - a.lat) * ky
        val px = (p.lng - a.lng) * kx; val py = (p.lat - a.lat) * ky
        val len2 = bx * bx + by * by
        val t = if (len2 == 0.0) 0.0 else ((px - ax) * bx + (py - ay) * by) / len2
        val c = t.coerceIn(0.0, 1.0)
        return c to hypot(px - bx * c, py - by * c)
    }

    /**
     * Where the rider is on the route. Searches the current step and the next two (riders skip short steps),
     * never going backwards, so a GPS jump cannot rewind the instructions.
     */
    fun progress(d: Directions, pos: LatLng, fromStep: Int = 0): NavState {
        if (d.steps.isEmpty()) return NavState(0, 0, 0, 0, 0, 0, pos)
        var best = Triple(fromStep.coerceIn(0, d.steps.lastIndex), 0, Double.MAX_VALUE) // step, segment, distance
        var bestT = 0.0
        for (si in best.first..min(d.steps.lastIndex, best.first + 2)) {
            val path = d.steps[si].path
            if (path.size < 2) continue
            for (k in 0 until path.size - 1) {
                val (t, dist) = project(pos, path[k], path[k + 1])
                if (dist < best.third - 0.5) { best = Triple(si, k, dist); bestT = t }
            }
        }
        val (si, k, off) = best
        val path = d.steps[si].path
        val snapped = if (path.size >= 2) Geo.lerp(path[k], path[k + 1], bestT) else pos
        val restOfStep = if (path.size >= 2) Geo.m(snapped, path[k + 1]) + pathLenM(path.subList(k + 1, path.size)) else 0.0
        val later = d.steps.drop(si + 1)
        val stepLen = max(1.0, pathLenM(path))
        val remainingS = d.steps[si].durationS * (restOfStep / stepLen) + later.sumOf { it.durationS }
        return NavState(si, restOfStep.roundToInt(), (restOfStep + later.sumOf { it.distanceM }).roundToInt(), remainingS.roundToInt(),
            if (off == Double.MAX_VALUE) 0 else off.roundToInt(), d.steps[si].legIndex, snapped)
    }

    /** The prompt to speak now, if the rider just crossed one of the prompt distances for this step. */
    fun promptFor(toManeuverM: Int, alreadySpoken: Set<Int>): Int? = PROMPTS_M.firstOrNull { toManeuverM <= it && it !in alreadySpoken }

    fun spoken(step: NavStep, inM: Int): String = when {
        inM <= 40 -> step.instruction
        inM < 1000 -> "In ${(inM / 10) * 10} metres, ${step.instruction.replaceFirstChar { it.lowercase() }}"
        else -> "In ${"%.1f".format(inM / 1000.0)} kilometres, ${step.instruction.replaceFirstChar { it.lowercase() }}"
    }

    /** Google encoded polyline (precision 5). */
    fun decodePolyline(enc: String): List<LatLng> {
        val out = ArrayList<LatLng>(); var i = 0; var lat = 0; var lng = 0
        while (i < enc.length) {
            var r = 0; var s = 0; var b: Int
            do { b = enc[i++].code - 63; r = r or ((b and 0x1f) shl s); s += 5 } while (b >= 0x20)
            lat += if (r and 1 != 0) (r shr 1).inv() else r shr 1
            r = 0; s = 0
            do { b = enc[i++].code - 63; r = r or ((b and 0x1f) shl s); s += 5 } while (b >= 0x20)
            lng += if (r and 1 != 0) (r shr 1).inv() else r shr 1
            out += LatLng(lat / 1e5, lng / 1e5)
        }
        return out
    }

    /**
     * Offline fallback when the Directions service can't be reached: one straight "head towards" step per stop,
     * so progress, ETA and arrival still work and the rider can hand over to Google Maps for the roads.
     */
    fun straightLine(start: LatLng, stops: List<Stop>, vehicle: String): Directions {
        var cur = start
        val steps = stops.mapIndexedNotNull { i, s ->
            val at = s.at ?: return@mapIndexedNotNull null
            val m = (Geo.m(cur, at) * Geo.ROAD_FACTOR).roundToInt()
            val dir = compass(Geo.bearing(cur, at))
            NavStep("Head $dir to ${s.label}", "straight", m, (m / (RoutePlanner.speedKmh(vehicle) / 3.6)).roundToInt(),
                (0..10).map { f -> Geo.lerp(cur, at, f / 10.0) }, i).also { cur = at }
        }
        return Directions(steps, steps.sumOf { it.distanceM }, steps.sumOf { it.durationS }, "straight_line")
    }

    fun compass(b: Double) = listOf("north", "north-east", "east", "south-east", "south", "south-west", "west", "north-west")[(((b + 22.5) % 360) / 45).toInt()]
}
