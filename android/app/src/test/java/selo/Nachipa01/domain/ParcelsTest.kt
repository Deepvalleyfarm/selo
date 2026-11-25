package selo.Nachipa01.domain

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ParcelsTest {
    @Test fun feesMatchSpecExamples() {
        assertEquals(5_250, ParcelFees.delivery(5.0)); assertEquals(3_000, ParcelFees.delivery(0.0))
        assertEquals(1_000, ParcelFees.sending(5_250)); assertEquals(1_800, ParcelFees.sending(12_000))
        assertEquals(Triple(4_672L, 525L, 53L), ParcelFees.split(5_250))
        assertEquals(500, ParcelFees.insurance(10_000)); assertEquals(10_000, ParcelFees.insurance(2_000_000))
    }

    @Test fun citiesMatchTheServerSeed() {
        val json = File("../../functions/src/data/cities.json").takeIf { it.exists() }?.readText() ?: return
        assertEquals(20, Cities.ALL.size)
        Cities.ALL.forEach { c -> assertTrue("${c.name} missing from cities.json", json.contains("\"name\": \"${c.name}\"")) }
        assertEquals(json.split("\"neighbourhoods\"").size - 1, Cities.ALL.size)
    }

    @Test fun cityDetectionAndSameCity() {
        assertEquals("Lusaka", Cities.cityOf(-15.42, 28.32)?.name)
        assertEquals("Chilanga", Cities.cityOf(-15.56, 28.27)?.name)
        assertNull(Cities.cityOf(-10.0, 30.0))
        assertNull(Cities.sameCityProblem(-15.42, 28.32, -15.40, 28.30))
        assertEquals("SeloNaChipa only supports same-city deliveries. Please enter a Lusaka address.", Cities.sameCityProblem(-15.42, 28.32, -12.96, 28.64))
        Cities.ALL.forEach { c -> c.neighbourhoods.forEach { n -> val (a, b) = Cities.neighbourhoodPoint(c, n); assertEquals(c.id, Cities.cityOf(a, b)?.id) } }
    }
}
