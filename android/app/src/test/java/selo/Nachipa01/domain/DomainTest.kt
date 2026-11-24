package selo.Nachipa01.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import selo.Nachipa01.data.model.PlatformConfig

class DomainTest {
    private val cfg = PlatformConfig()

    @Test fun pinRules() {
        assertNull(Validation.pinProblem("48291"))
        assertNotNull(Validation.pinProblem("1234"))
        assertNotNull(Validation.pinProblem("12345"))
        assertNotNull(Validation.pinProblem("11111"))
        assertNotNull(Validation.pinProblem("4829a"))
    }

    @Test fun msisdn() {
        assertEquals("+260971234512", Validation.normaliseMsisdn("097 123 4512"))
        assertEquals("+260961234512", Validation.normaliseMsisdn("+260 96-123-4512"))
        assertNull(Validation.normaliseMsisdn("0811234512"))
        assertEquals("097 ••• ••12", Validation.maskMsisdn("+260971234512"))
    }

    @Test fun answersNormalise() {
        assertEquals("manchester united", Validation.normaliseAnswer("  Manchester   UNITED "))
    }

    @Test fun escrowSplitMatchesPrdExample() {
        // Goods K200, delivery K40: seller K195, platform K5, rider K39.20, fund K0.80
        val s = Fees.split(20_000, 4_000, cfg)
        assertEquals(19_500, s.sellerNetNgwee)
        assertEquals(500, s.platformFeeNgwee)
        assertEquals(3_920, s.riderNetNgwee)
        assertEquals(80, s.socialFundNgwee)
    }

    @Test fun parcelInsurance() {
        val q = Fees.quote(0, 7.4, cfg.copy(deliveryFeePerKm = 5.0, deliveryBaseFee = 5.0), 350_000)
        assertEquals(7_000, q.insurancePremiumNgwee)
        assertEquals(4_200, q.deliveryNgwee)
        assertEquals(11_200, q.totalNgwee)
    }

    @Test fun agentCommission() {
        val (gross, net) = Fees.agentCommission(20_000, 10.0, cfg)
        assertEquals(2_000, gross); assertEquals(1_900, net)
    }

    @Test fun formatting() { assertEquals("K 1,240.50", Fees.formatK(124_050)) }
}
