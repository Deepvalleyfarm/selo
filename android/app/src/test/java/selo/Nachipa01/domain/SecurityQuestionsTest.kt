package selo.Nachipa01.domain

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import selo.Nachipa01.data.model.Role
import selo.Nachipa01.data.model.SecurityQuestion
import selo.Nachipa01.data.repo.DemoBackend
import selo.Nachipa01.data.repo.QuestionAnswer
import selo.Nachipa01.data.repo.RegistrationRequest
import kotlin.random.Random

class SecurityQuestionsTest {
    @Test fun bankHasTwelveDistinctQuestions() {
        assertEquals(12, SecurityQuestions.BANK.size)
        assertEquals(12, SecurityQuestions.BANK.map { it.id }.toSet().size)
    }

    @Test fun offersFourDistinctQuestionsFromTheBank() {
        repeat(50) { seed ->
            val o = SecurityQuestions.offer(SecurityQuestions.BANK, random = Random(seed))
            assertEquals(4, o.size)
            assertEquals(4, o.map { it.id }.toSet().size)
            assertTrue(o.all { q -> SecurityQuestions.BANK.any { it.id == q.id } })
        }
    }

    @Test fun shuffleChangesTheOffer() {
        val sets = (0 until 20).map { SecurityQuestions.offer(SecurityQuestions.BANK, random = Random(it)).map { q -> q.id }.toSet() }.toSet()
        assertTrue("expected different question sets across shuffles", sets.size > 5)
    }

    @Test fun reshuffleAvoidsThePreviousFour() {
        val first = SecurityQuestions.offer(SecurityQuestions.BANK, random = Random(1)).map { it.id }.toSet()
        val second = SecurityQuestions.offer(SecurityQuestions.BANK, random = Random(2), exclude = first)
        assertTrue(second.none { it.id in first })
    }

    @Test fun emptyServerBankFallsBackToBuiltIn() {
        assertEquals(SecurityQuestions.BANK, SecurityQuestions.merge(emptyList()))
        val merged = SecurityQuestions.merge(listOf(SecurityQuestion("q_first_job", "What was your very first job?")))
        assertEquals("What was your very first job?", SecurityQuestions.textFor("q_first_job", merged))
        assertEquals(12, merged.size)
    }

    /** Forgot PIN must ask exactly the 3 questions chosen at sign-up, for every role. */
    @Test fun resetAsksTheQuestionsChosenAtSignUp() = runBlocking {
        Role.entries.forEachIndexed { i, role ->
            val backend = DemoBackend()
            val chosen = SecurityQuestions.offer(SecurityQuestions.BANK, random = Random(i)).take(3)
            val msisdn = "+26097123450$i"
            backend.register(RegistrationRequest(role, msisdn, "48291", chosen.map { QuestionAnswer(it.id, "  Answer ${it.id} ") },
                mapOf("fullName" to "Test ${role.label}"), null, null, emptyMap()))
            val asked = backend.recoveryQuestions(msisdn)!!
            assertEquals(chosen.map { it.id }, asked.map { it.id })
            assertEquals(chosen.map { it.text }, asked.map { it.text })
            // case- and space-insensitive match passes; a wrong answer fails
            assertNotNull(backend.verifyAnswers(msisdn, chosen.map { QuestionAnswer(it.id, "answer ${it.id}") }))
            assertThrows(Exception::class.java) { runBlocking { backend.verifyAnswers(msisdn, chosen.map { QuestionAnswer(it.id, "wrong") }) } }
        }
    }
}
