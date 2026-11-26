package selo.Nachipa01.domain

import kotlin.math.cos

/** Geohash (same algorithm as the server) for "shops near me" queries on stores.geohash5 (~4.9 km cells). */
object GeoHash {
    private const val B32 = "0123456789bcdefghjkmnpqrstuvwxyz"
    fun encode(lat: Double, lng: Double, precision: Int = 9): String {
        val latR = doubleArrayOf(-90.0, 90.0); val lngR = doubleArrayOf(-180.0, 180.0)
        val sb = StringBuilder(); var bit = 0; var ch = 0; var even = true
        while (sb.length < precision) {
            val r = if (even) lngR else latR; val v = if (even) lng else lat; val mid = (r[0] + r[1]) / 2
            if (v >= mid) { ch = ch or (1 shl (4 - bit)); r[0] = mid } else r[1] = mid
            even = !even
            if (++bit == 5) { sb.append(B32[ch]); bit = 0; ch = 0 }
        }
        return sb.toString()
    }
    /** The precision-5 cell containing the point and its 8 neighbours (sampled ~4.5 km away). */
    fun cellsAround(lat: Double, lng: Double): List<String> {
        val dLat = 4.5 / 110.574; val dLng = 4.5 / (111.320 * cos(Math.toRadians(lat)))
        return (-1..1).flatMap { i -> (-1..1).map { j -> encode(lat + i * dLat, lng + j * dLng, 5) } }.distinct()
    }
}
