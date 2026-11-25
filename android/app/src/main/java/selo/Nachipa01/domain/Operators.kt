package selo.Nachipa01.domain

import selo.Nachipa01.R

/** Zambian mobile-money operators, identified from the number prefix (PRD v6.2 payments). */
enum class Operator(val label: String, val prefixes: Set<String>, val logo: Int, val mark: Int, val brand: Long) {
    AIRTEL("Airtel Money", setOf("97", "77"), R.drawable.op_airtel, R.drawable.op_airtel_mark, 0xFFED1C24),
    MTN("MTN Money", setOf("96", "76"), R.drawable.op_mtn, R.drawable.op_mtn_mark, 0xFFFFCB05),
    ZAMTEL("Zamtel Money", setOf("95", "75"), R.drawable.op_zamtel, R.drawable.op_zamtel_mark, 0xFF2BB24C);

    companion object {
        /**
         * Detects the operator as soon as the prefix is typed: "0977", "+260 96…", "26095…", "77…".
         * Returns null until enough digits are entered or when the prefix isn't a mobile-money network.
         */
        fun detect(raw: String): Operator? {
            val d = raw.filter(Char::isDigit)
            val national = when {
                d.startsWith("260") -> d.drop(3)
                d.startsWith("0") -> d.drop(1)
                else -> d
            }
            if (national.length < 2) return null
            return entries.firstOrNull { national.take(2) in it.prefixes }
        }

        /** True once the input has a prefix but it isn't Airtel, MTN or Zamtel. */
        fun unsupported(raw: String): Boolean {
            val d = raw.filter(Char::isDigit).removePrefix("260").removePrefix("0")
            return d.length >= 2 && detect(raw) == null
        }
    }
}
