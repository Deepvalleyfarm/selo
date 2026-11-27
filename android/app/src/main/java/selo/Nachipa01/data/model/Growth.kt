package selo.Nachipa01.data.model

/* ----------------------------------------------------------------- Marketing Centre */
enum class CampaignType(val label: String, val blurb: String) {
    DISCOUNT("Discount", "A % off selected products"),
    BOGO("Buy 1 Get 1 Free", "Every second item is free"),
    FLASH("Limited-time offer", "A % off that ends soon (max 7 days)"),
    BUNDLE("Product bundle", "2–5 products together at one price"),
}
data class Campaign(
    val id: String, val type: CampaignType, val title: String, val listingIds: List<String>, val discountPct: Int? = null,
    val bundlePriceNgwee: Long? = null, val startsAt: Long, val endsAt: Long?, val maxUnits: Int? = null, val status: String = "ACTIVE",
    val unitsSold: Int = 0, val revenueNgwee: Long = 0, val orders: Int = 0, val label: String = "", val live: Boolean = false,
)
data class CampaignDraft(
    val id: String? = null, val type: CampaignType, val title: String, val listingIds: List<String>, val discountPct: Int? = null,
    val bundlePriceNgwee: Long? = null, val startsAt: Long, val endsAt: Long?, val maxUnits: Int? = null,
)
/** Mirrors pricing.ts campaignProblem so sellers see the problem before sending. */
fun campaignProblem(c: CampaignDraft, now: Long = System.currentTimeMillis()): String? = when {
    c.title.trim().length < 3 -> "Give the campaign a name."
    c.listingIds.isEmpty() -> "Choose at least one product."
    c.type == CampaignType.BUNDLE && c.listingIds.size !in 2..5 -> "A bundle needs 2 to 5 products."
    (c.type == CampaignType.DISCOUNT || c.type == CampaignType.FLASH) && (c.discountPct ?: 0) !in 1..90 -> "Set a discount between 1% and 90%."
    c.type == CampaignType.BUNDLE && (c.bundlePriceNgwee ?: 0) <= 0 -> "Set the bundle price."
    c.endsAt != null && c.endsAt <= c.startsAt -> "The end time must be after the start."
    c.type == CampaignType.FLASH && (c.endsAt == null || c.endsAt - maxOf(c.startsAt, now) > 7 * 86_400_000L) -> "A limited-time offer must end within 7 days."
    else -> null
}

/* ----------------------------------------------------------------- AI Ad Generator */
data class AdPack(
    val id: String, val storyUrl: String?, val squareUrl: String?, val productName: String, val headline: String, val description: String,
    val whatsapp: String, val facebook: String, val tiktok: String, val hashtags: List<String>,
)

/* ----------------------------------------------------------------- Monthly report */
data class ReportMetric(val key: String, val label: String, val value: Long, val previous: Long, val unit: String) {
    val changePct: Int? get() = if (previous > 0 && unit != "pct") (((value - previous) * 100) / previous).toInt() else null
}
data class TopProduct(val title: String, val units: Int, val revenueNgwee: Long)
data class BreakdownRow(val label: String, val value: String, val sub: String? = null)
data class ReportBreakdown(val title: String, val rows: List<BreakdownRow>)
/** Role-specific monthly report: the metrics, breakdowns, highlights and tips differ for sellers, riders and agents. */
data class PerformanceReport(val month: String, val monthLabel: String, val role: String, val metrics: List<ReportMetric>, val highlights: List<String>,
                             val tips: List<String>, val top: List<TopProduct> = emptyList(), val breakdowns: List<ReportBreakdown> = emptyList(),
                             val highlightsTitle: String = "Highlights", val tipsTitle: String = "Tips for next month")

/** Where a rider takes parcel jobs: a city plus chosen neighbourhoods, or the whole city. */
data class RiderAreas(val cityId: String, val cityName: String, val wholeCity: Boolean, val neighbourhoods: List<String>)

/* ----------------------------------------------------------------- Rider Safety Centre */
data class SafetyStatus(
    val emergencyName: String?, val emergencyPhone: String?, val insurance: Boolean, val premiumNgwee: Long, val coverNgwee: Long,
    val lessons: Set<String>, val evidenceCount: Int, val openAlertId: String? = null, val openAlertUrl: String? = null,
    /** Rider cover stays off until finance configures a confirmed insurer and switches it on in the back office. */
    val coverAvailable: Boolean = false, val insurerName: String? = null, val policyNumber: String? = null, val claimsPhone: String? = null,
    val claimsEmail: String? = null, val termsUrl: String? = null, val benefits: String? = null,
)
data class SosResult(val alertId: String, val shareUrl: String, val mapsUrl: String, val contactNotified: Boolean, val hasContact: Boolean, val contactName: String?)
data class RiderIdCard(val code: String, val verifyUrl: String, val name: String, val photoUrl: String?, val plate: String, val vehicle: String,
                       val verified: Boolean, val status: String, val memberSince: String)
data class Lesson(val id: String, val title: String, val minutes: Int, val points: List<String>)
