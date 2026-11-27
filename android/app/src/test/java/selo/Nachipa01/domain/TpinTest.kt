package selo.Nachipa01.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class TpinTest {
    @Test fun inputKeepsTenDigitsOnly() {
        assertEquals("1002345678", Validation.formatTpin("TPIN 100-234-5678 99"))
        assertEquals("123", Validation.formatTpin("ab1c2d3"))
    }
    @Test fun exactlyTenDigits() {
        assertNull(Validation.tpinProblem("1002345678"))
        listOf("100234567", "10023456789", "TPIN100234", "1002 34567", "").forEach { assertNotNull(it, Validation.tpinProblem(it)) }
    }
}
