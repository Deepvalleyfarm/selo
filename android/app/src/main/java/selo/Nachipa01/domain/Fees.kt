package selo.Nachipa01.domain

import selo.Nachipa01.data.model.PlatformConfig
import kotlin.math.roundToLong

/** All amounts are in ngwee (K1 = 100 ngwee) to avoid floating point drift. */
data class OrderQuote(
    val goodsNgwee: Long,
    val deliveryNgwee: Long,
    val serviceFeeNgwee: Long,
    val insurancePremiumNgwee: Long,
    /** Transaction (payment processing) fee, applied to everything else the buyer pays. */
    val processingFeeNgwee: Long = 0,
) {
    val totalNgwee get() = goodsNgwee + deliveryNgwee + serviceFeeNgwee + insurancePremiumNgwee + processingFeeNgwee
}

data class PayoutSplit(
    val sellerNetNgwee: Long,
    val platformFeeNgwee: Long,
    val riderNetNgwee: Long,
    val riderPlatformFeeNgwee: Long,
    val socialFundNgwee: Long,
)

object Fees {
    fun kwachaToNgwee(k: Double) = (k * 100).roundToLong()

    fun deliveryFee(distanceKm: Double, cfg: PlatformConfig): Long =
        kwachaToNgwee(cfg.deliveryBaseFee + distanceKm * cfg.deliveryFeePerKm)

    fun quote(goodsNgwee: Long, distanceKm: Double, cfg: PlatformConfig, declaredValueNgwee: Long = 0): OrderQuote {
        val delivery = deliveryFee(distanceKm, cfg)
        val service = (goodsNgwee * cfg.buyerServiceFeePct / 100.0).roundToLong()
        val capped = declaredValueNgwee.coerceAtMost(kwachaToNgwee(cfg.insuranceMaxDeclaredValue))
        val premium = (capped * cfg.insurancePremiumPct / 100.0).roundToLong()
        val processing = ((goodsNgwee + delivery + service + premium) * cfg.paymentProcessingPct / 100.0).roundToLong()
        return OrderQuote(goodsNgwee, delivery, service, premium, processing)
    }

    /** Seller leg at PICKED_UP, rider leg at DELIVERED (Escrow Engine spec). */
    fun split(goodsNgwee: Long, deliveryNgwee: Long, cfg: PlatformConfig): PayoutSplit {
        val platform = kwachaToNgwee(cfg.flatPlatformFeeKwacha).coerceAtMost(goodsNgwee)
        val riderFee = (deliveryNgwee * cfg.riderPlatformFeePct / 100.0).roundToLong()
        val fund = (deliveryNgwee * cfg.socialFundPct / 100.0).roundToLong()
        return PayoutSplit(
            sellerNetNgwee = goodsNgwee - platform,
            platformFeeNgwee = platform,
            riderNetNgwee = deliveryNgwee - riderFee - fund,
            riderPlatformFeeNgwee = riderFee,
            socialFundNgwee = fund,
        )
    }

    fun agentCommission(saleNgwee: Long, ratePct: Double, cfg: PlatformConfig): Pair<Long, Long> {
        val gross = (saleNgwee * ratePct / 100.0).roundToLong()
        val cut = (gross * cfg.agentPlatformCutPct / 100.0).roundToLong()
        return gross to gross - cut
    }

    fun formatK(ngwee: Long): String {
        val k = ngwee / 100
        val n = kotlin.math.abs(ngwee % 100)
        val grouped = "%,d".format(k)
        return "K $grouped.${n.toString().padStart(2, '0')}"
    }

    /** Boost: price per day by reach × days. Feed Story: flat price for 24 h. Mirrors functions/src/promotions.ts promoPrice. */
    fun promoPriceNgwee(type: String, reach: selo.Nachipa01.data.model.BoostReach, days: Int, cfg: PlatformConfig): Long =
        if (type == "story") kwachaToNgwee(cfg.storyPrice24h)
        else kwachaToNgwee(when (reach) {
            selo.Nachipa01.data.model.BoostReach.LOCAL -> cfg.boostLocalPerDay
            selo.Nachipa01.data.model.BoostReach.REGIONAL -> cfg.boostRegionalPerDay
            selo.Nachipa01.data.model.BoostReach.NATIONAL -> cfg.boostNationalPerDay
        }) * days.coerceIn(1, 30)
}
