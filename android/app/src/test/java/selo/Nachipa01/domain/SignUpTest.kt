package selo.Nachipa01.domain

import org.junit.Assert.*
import org.junit.Test
import selo.Nachipa01.ui.components.MsisdnInput

/** +260 phone input, NRC formatting and ID validation (mirrors the server). */
class SignUpTest {
    @Test fun phoneFieldsKeepOnlyTheNineDigitsAfter260() {
        assertEquals("977123456", MsisdnInput.national("0977 123 456"))
        assertEquals("977123456", MsisdnInput.national("+260977123456"))
        assertEquals("977123456", MsisdnInput.national("260977123456"))
        assertEquals("97712", MsisdnInput.national("97712"))
        assertEquals("+260977123456", MsisdnInput.full("0977123456"))
        assertEquals("", MsisdnInput.full(""))
        assertEquals("+260977123456", Validation.normaliseMsisdn(MsisdnInput.full("977123456"))?.let { "+260${it.takeLast(9)}" })
        assertNotNull(MsisdnInput.problem("+260211123456")); assertNull(MsisdnInput.problem("+260977123456")); assertNull(MsisdnInput.problem("+26097"))
    }

    @Test fun nrcIsFormattedWhileTyping() {
        assertEquals("123456", Validation.formatNrc("123456"))
        assertEquals("123456/7", Validation.formatNrc("1234567"))
        assertEquals("123456/78/1", Validation.formatNrc("123456781"))
        assertEquals("123456/78/1", Validation.formatNrc("123456/78/1999"))
        assertEquals("123456/78/1", Validation.formatNrc("12a3456-78 1"))
    }

    @Test fun idValidationMatchesServer() {
        assertNull(Validation.idProblem("NRC", "123456/78/1"))
        assertNotNull(Validation.idProblem("NRC", "123456/78"))
        assertNull(Validation.idProblem("Passport", "ZN123456")); assertNotNull(Validation.idProblem("Passport", "Z1"))
        assertNull(Validation.idProblem("Driver's licence", "DL-2201934"))
        assertNotNull(Validation.idProblem("", "123"))
        assertEquals(listOf("idFront"), Validation.idPhotos("Passport").map { it.first })
        assertEquals(listOf("idFront", "idBack"), Validation.idPhotos("NRC").map { it.first })
    }
}
