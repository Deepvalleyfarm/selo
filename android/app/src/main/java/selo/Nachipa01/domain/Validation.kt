package selo.Nachipa01.domain

/** Rules shared by onboarding, login and PIN reset. Mirrors functions/src/domain.ts. */
object Validation {
    const val PIN_LENGTH = 5

    private val weakPins = setOf("00000", "11111", "22222", "33333", "44444", "55555",
        "66666", "77777", "88888", "99999", "12345", "54321", "01234", "98765", "43210")

    /** Returns null when the PIN is acceptable, otherwise a user-facing message. */
    fun pinProblem(pin: String): String? = when {
        pin.length != PIN_LENGTH || !pin.all { it.isDigit() } -> "PIN must be $PIN_LENGTH digits."
        pin in weakPins -> "That PIN is too easy to guess. Avoid repeated or sequential digits."
        else -> null
    }

    /**
     * Normalises a Zambian mobile number to E.164 (+2609XXXXXXXX).
     * Accepts 09XXXXXXXX, 9XXXXXXXX, 2609XXXXXXXX and +2609XXXXXXXX with spaces or dashes.
     */
    fun normaliseMsisdn(raw: String): String? {
        val digits = raw.filter { it.isDigit() }
        val national = when {
            digits.startsWith("260") && digits.length == 12 -> digits.substring(3)
            digits.startsWith("0") && digits.length == 10 -> digits.substring(1)
            digits.length == 9 -> digits
            else -> return null
        }
        // Airtel 097/077, MTN 096/076, Zamtel 095/075
        if (national.take(2) !in setOf("97", "77", "96", "76", "95", "75")) return null
        return "+260$national"
    }

    fun maskMsisdn(e164: String): String {
        val n = e164.removePrefix("+260")
        return if (n.length == 9) "0${n.take(2)} ••• ••${n.takeLast(2)}" else e164
    }

    fun network(e164: String): String = Operator.detect(e164)?.label ?: "Mobile money"

    /** Lowercase, trim and collapse internal whitespace before hashing (server side). */
    fun normaliseAnswer(answer: String): String =
        answer.trim().lowercase().replace(Regex("\\s+"), " ")

    fun answerProblem(answer: String): String? =
        if (normaliseAnswer(answer).length < 2) "Answer must be at least 2 characters." else null
}
