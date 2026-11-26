package selo.Nachipa01.domain

import org.junit.Assert.*
import org.junit.Test
import selo.Nachipa01.data.model.BoostReach
import selo.Nachipa01.data.model.PlatformConfig

class PaymentsTest {
    @Test fun detectsOperatorFromPrefix() {
        assertEquals(Operator.AIRTEL, Operator.detect("0977"))
        assertEquals(Operator.AIRTEL, Operator.detect("0771 234 567"))
        assertEquals(Operator.MTN, Operator.detect("0966"))
        assertEquals(Operator.MTN, Operator.detect("+260 76 123 4567"))
        assertEquals(Operator.ZAMTEL, Operator.detect("0955"))
        assertEquals(Operator.ZAMTEL, Operator.detect("260751234567"))
        assertNull(Operator.detect("09"))
        assertNull(Operator.detect("0211"))
        assertTrue(Operator.unsupported("0211 25"))
        assertFalse(Operator.unsupported("097"))
        assertEquals("Zamtel Money", Validation.network("+260955123456"))
    }

    @Test fun totalIncludesDeliveryPlatformAndTransactionFees() {
        val q = Fees.quote(20_000, 4.0, PlatformConfig(deliveryBaseFee = 5.0, deliveryFeePerKm = 5.0, buyerServiceFeePct = 2.0, paymentProcessingPct = 1.0))
        assertEquals(2_500, q.deliveryNgwee); assertEquals(400, q.serviceFeeNgwee); assertEquals(229, q.processingFeeNgwee)
        assertEquals(23_129, q.totalNgwee)
        assertEquals(0, Fees.quote(20_000, 4.0, PlatformConfig(paymentProcessingPct = 0.0)).processingFeeNgwee)
    }

    @Test fun deliveryFeeEngineDefaultsMatchServer() {
        // base K45 + K5.50/km on Routes API road distance: 8 km → K89
        assertEquals(8_900, Fees.quote(0, 8.0, PlatformConfig()).deliveryNgwee)
    }

    @Test fun promotionPricesMatchServer() {
        val c = PlatformConfig()
        assertEquals(6_000, Fees.promoPriceNgwee("boost", BoostReach.LOCAL, 3, c))
        assertEquals(31_500, Fees.promoPriceNgwee("boost", BoostReach.REGIONAL, 7, c))
        assertEquals(9_000, Fees.promoPriceNgwee("boost", BoostReach.NATIONAL, 1, c))
        assertEquals(1_000, Fees.promoPriceNgwee("story", BoostReach.NATIONAL, 14, c))
        assertEquals(60_000, Fees.promoPriceNgwee("boost", BoostReach.LOCAL, 99, c))
    }
}
