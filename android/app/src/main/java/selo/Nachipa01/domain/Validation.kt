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

    /* ---------------- sign-up identity (NRC · passport · driver's licence) */
    val ID_TYPES = listOf("NRC", "Passport", "Driver's licence")

    /** Formats NRC input as XXXXXX/XX/X while the user types digits only. */
    fun formatNrc(raw: String): String {
        val d = raw.filter(Char::isDigit).take(9)
        return buildString {
            append(d.take(6))
            if (d.length > 6) append('/').append(d.substring(6, minOf(8, d.length)))
            if (d.length > 8) append('/').append(d[8])
        }
    }
    fun formatIdNumber(type: String, raw: String): String = when (type) {
        "NRC" -> formatNrc(raw)
        "Passport" -> raw.uppercase().filter { it.isLetterOrDigit() }.take(9)
        else -> raw.uppercase().filter { it.isLetterOrDigit() || it == '-' }.take(15)
    }
    /** Same rules as the server (auth.ts identityOf). */
    fun idProblem(type: String, number: String): String? = when {
        type !in ID_TYPES -> "Choose your ID type: NRC, passport or driver's licence."
        number.isBlank() -> "Enter your $type number."
        type == "NRC" && !Regex("^\\d{6}/\\d{2}/\\d$").matches(number) -> "Enter your NRC number as XXXXXX/XX/X (for example 123456/78/1)."
        type == "Passport" && !Regex("^[A-Z0-9]{6,9}$").matches(number) -> "Enter your passport number as printed (6–9 letters and digits, e.g. ZN123456)."
        type == "Driver's licence" && !Regex("^[A-Z0-9-]{5,15}$").matches(number) -> "Enter your driver's licence number as printed (5–15 letters and digits)."
        else -> null
    }
    /** Photos each ID type needs: NRC and licence front + back, passport photo page. */
    /** Corporate seller / agency details. Same rules as the server (auth.ts corporateOf). */
    fun corporateProblem(name: String, regNumber: String, tpin: String, address: String): String? = when {
        name.trim().length < 2 -> "Enter the registered name of the organisation."
        !Regex("^[A-Z0-9/-]{5,20}$").matches(regNumber.trim().uppercase()) -> "Enter the company registration number as on the PACRA certificate (5–20 letters and digits)."
        !Regex("^\\d{10}$").matches(tpin.filter(Char::isDigit)) -> "Enter the organisation's 10-digit TPIN."
        address.trim().length < 5 -> "Enter the organisation's registered address."
        else -> null
    }

    fun idPhotos(type: String): List<Pair<String, String>> = when (type) {
        "Passport" -> listOf("idFront" to "Passport photo page")
        "Driver's licence" -> listOf("idFront" to "Licence front", "idBack" to "Licence back")
        else -> listOf("idFront" to "NRC front", "idBack" to "NRC back")
    }
}
