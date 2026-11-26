package selo.Nachipa01.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import selo.Nachipa01.data.model.CampaignDraft
import selo.Nachipa01.data.model.CampaignType
import selo.Nachipa01.data.model.ReportMetric
import selo.Nachipa01.data.model.campaignProblem

class GrowthTest {
    private val now = 1_800_000_000_000L
    private fun d(t: CampaignType, ids: List<String> = listOf("a"), pct: Int? = null, bundle: Long? = null, ends: Long? = now + 3_600_000) =
        CampaignDraft(type = t, title = "Deal", listingIds = ids, discountPct = pct, bundlePriceNgwee = bundle, startsAt = now, endsAt = ends)

    @Test fun campaignRulesMatchServer() {
        assertNull(campaignProblem(d(CampaignType.BOGO, ends = null), now))
        assertEquals("Set a discount between 1% and 90%.", campaignProblem(d(CampaignType.DISCOUNT, pct = 95), now))
        assertEquals("A bundle needs 2 to 5 products.", campaignProblem(d(CampaignType.BUNDLE, bundle = 500), now))
        assertEquals("A limited-time offer must end within 7 days.", campaignProblem(d(CampaignType.FLASH, pct = 20, ends = now + 8 * 86_400_000L), now))
        assertNull(campaignProblem(d(CampaignType.BUNDLE, listOf("a", "b"), bundle = 500), now))
    }
    @Test fun reportChange() {
        assertEquals(24, ReportMetric("sales", "Sales", 124, 100, "ngwee").changePct)
        assertNull(ReportMetric("acceptance", "Acceptance", 90, 80, "pct").changePct)
    }
}
