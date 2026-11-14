package bd.family.expenses.money

/**
 * Exact money handling, mirroring backend/app/money.py. Amounts are Long poisha
 * (1 taka = 100 poisha). Never use Float/Double for money.
 */
object Taka {
  const val MAX_TRANSACTION_POISHA: Long = 100_000_000_000L

  // Plain digits, Western grouping (1,250,000) or South Asian grouping
  // (12,50,000). "12,50" is rejected so a decimal comma can never be misread.
  private val PATTERN =
    Regex("""^(\d{1,15}|\d{1,3}(?:,\d{3})+|\d{1,2}(?:,\d{2})*,\d{3})(?:\.(\d{1,2}))?$""")

  private const val BANGLA_DIGITS = "০১২৩৪৫৬৭৮৯"

  sealed interface Parsed {
    data class Ok(val poisha: Long) : Parsed

    /** Not a number with at most two decimals. */
    data object BadFormat : Parsed

    /** Zero, or above the per-transaction maximum. */
    data object OutOfRange : Parsed
  }

  /** Parse user-typed taka ("120", "120.50", "১২০.৫০", "1,250") into poisha. */
  fun parse(input: String): Parsed {
    val latin = toLatinDigits(input.trim())
    val match = PATTERN.matchEntire(latin) ?: return Parsed.BadFormat
    val whole = match.groupValues[1].replace(",", "").toLongOrNull() ?: return Parsed.BadFormat
    val fraction = match.groupValues[2].padEnd(2, '0').ifEmpty { "00" }.toLong()
    if (whole > MAX_TRANSACTION_POISHA / 100) return Parsed.OutOfRange
    val poisha = whole * 100 + fraction
    if (poisha <= 0 || poisha > MAX_TRANSACTION_POISHA) return Parsed.OutOfRange
    return Parsed.Ok(poisha)
  }

  const val MAX_ABS_OPENING_POISHA: Long = 10_000_000_000_000L

  /**
   * A signed amount (zero and negatives allowed), as for an account's starting
   * balance. Returns null when invalid or out of range.
   */
  fun parseSigned(input: String): Long? {
    val text = toLatinDigits(input.trim())
    val negative = text.startsWith("-") || text.startsWith("−")
    val unsigned = if (negative) text.drop(1).trim() else text
    val match = PATTERN.matchEntire(unsigned) ?: return null
    val whole = match.groupValues[1].replace(",", "").toLongOrNull() ?: return null
    if (whole > MAX_ABS_OPENING_POISHA / 100) return null
    val poisha = whole * 100 + match.groupValues[2].padEnd(2, '0').toLong()
    if (poisha > MAX_ABS_OPENING_POISHA) return null
    return if (negative) -poisha else poisha
  }

  /** "৳1,25,000.50" (South Asian grouping); Bangla digits when [bangla]. */
  fun format(poisha: Long, bangla: Boolean, showSign: Boolean = false): String {
    val negative = poisha < 0
    // Long.MIN_VALUE cannot be negated; amounts are bounded far below it.
    val abs = if (negative) -poisha else poisha
    val text = "${group(abs / 100)}.${(abs % 100).toString().padStart(2, '0')}"
    val sign = when {
      negative -> "−"
      showSign && poisha > 0 -> "+"
      else -> ""
    }
    val result = "$sign৳$text"
    return if (bangla) toBanglaDigits(result) else result
  }

  /** "120.50" for pre-filling an amount field. */
  fun toInputText(poisha: Long): String =
    "${poisha / 100}.${(poisha % 100).toString().padStart(2, '0')}"

  fun toBanglaDigits(text: String): String =
    buildString(text.length) { text.forEach { append(if (it in '0'..'9') BANGLA_DIGITS[it - '0'] else it) } }

  fun toLatinDigits(text: String): String =
    buildString(text.length) {
      text.forEach {
        val index = BANGLA_DIGITS.indexOf(it)
        append(if (index >= 0) '0' + index else it)
      }
    }

  /** South Asian digit grouping: last three digits, then pairs (1,25,00,000). */
  private fun group(whole: Long): String {
    val digits = whole.toString()
    if (digits.length <= 3) return digits
    val head = digits.dropLast(3)
    val tail = digits.takeLast(3)
    val pairs = head.reversed().chunked(2).joinToString(",").reversed()
    return "$pairs,$tail"
  }
}
