package selo.Nachipa01.domain

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/** Parcels v2 §3 fee engine — mirrors functions/src/parcel-rules.ts (the server is authoritative; this is for previews and demo). */
object ParcelFees {
    data class Cfg(val base: Double = 30.0, val perKm: Double = 4.5, val minFee: Double = 30.0, val sendingPct: Double = 15.0, val sendingCapPct: Double = 50.0,
                   val minSending: Double = 10.0, val platformPct: Double = 10.0, val fundPct: Double = 1.0, val insurancePct: Double = 2.0,
                   val insuranceMaxDeclared: Double = 5000.0, val insuranceMin: Double = 5.0)
    private fun n(k: Double) = (k * 100).roundToLong()

    fun delivery(km: Double, c: Cfg = Cfg()) = max(n(c.minFee), (n(c.base) + n(c.perKm) * max(0.0, km)).let { kotlin.math.round(it.toDouble()).toLong() })
    fun sending(deliveryNgwee: Long, c: Cfg = Cfg()): Long {
        val raw = (deliveryNgwee * c.sendingPct / 100).roundToLong()
        val capped = min(raw, (deliveryNgwee * c.sendingCapPct / 100).roundToLong())
        return max(capped, n(c.minSending))
    }
    fun insurance(declaredNgwee: Long, c: Cfg = Cfg()): Long =
        if (declaredNgwee <= 0) 0 else max(n(c.insuranceMin), (min(declaredNgwee, n(c.insuranceMaxDeclared)) * c.insurancePct / 100).roundToLong())
    /** platform 10%, fund 1%, rider gets the rest (rounding residue to the rider). */
    fun split(deliveryNgwee: Long, c: Cfg = Cfg()): Triple<Long, Long, Long> {
        val p = (deliveryNgwee * c.platformPct / 100).roundToLong(); val f = (deliveryNgwee * c.fundPct / 100).roundToLong()
        return Triple(deliveryNgwee - p - f, p, f)
    }
}
