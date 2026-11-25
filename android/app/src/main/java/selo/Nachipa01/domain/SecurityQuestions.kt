package selo.Nachipa01.domain

import selo.Nachipa01.data.model.SecurityQuestion
import kotlin.random.Random

/**
 * The 12-question bank. IDs are stable and match functions/src/domain-defaults.ts and the
 * securityQuestionBank collection; Firestore wording (editable in the back office) overrides
 * these texts when it can be read, but the app never depends on it to show the questions.
 */
object SecurityQuestions {
    const val OFFERED = 4
    const val REQUIRED = 3

    val BANK: List<SecurityQuestion> = listOf(
        "q_mother_maiden_name" to "What is your mother's maiden name?",
        "q_first_school" to "What was the name of your first school?",
        "q_favourite_dish" to "What is your favourite traditional Zambian dish?",
        "q_birth_town" to "What is the name of the village or town where you were born?",
        "q_childhood_nickname" to "What was your childhood nickname?",
        "q_best_friend" to "What is the first name of your best friend growing up?",
        "q_favourite_teacher" to "What is the name of your favourite teacher?",
        "q_first_phone" to "What was the make of your first phone?",
        "q_father_first_name" to "What is your father's first name?",
        "q_childhood_street" to "What is the name of the street you grew up on?",
        "q_football_team" to "What is your favourite football team?",
        "q_first_job" to "What was your first job?",
    ).map { SecurityQuestion(it.first, it.second) }

    /** Built-in bank with any server wording applied; server-only questions are added too. */
    fun merge(server: List<SecurityQuestion>): List<SecurityQuestion> {
        val byId = server.filter { it.text.isNotBlank() }.associateBy { it.id }
        return BANK.map { byId[it.id] ?: it } + server.filter { s -> s.text.isNotBlank() && BANK.none { it.id == s.id } }
    }

    /** Shuffles the bank and returns [count] distinct questions, never repeating [exclude] when possible. */
    fun offer(bank: List<SecurityQuestion>, count: Int = OFFERED, random: Random = Random.Default, exclude: Set<String> = emptySet()): List<SecurityQuestion> {
        val pool = bank.distinctBy { it.id }.ifEmpty { BANK }
        val fresh = pool.filter { it.id !in exclude }
        val source = if (fresh.size >= count) fresh else pool
        return source.shuffled(random).take(count)
    }

    fun textFor(id: String, bank: List<SecurityQuestion> = BANK) = bank.firstOrNull { it.id == id }?.text ?: BANK.firstOrNull { it.id == id }?.text ?: id
}
