package bd.family.expenses.ui.entry

import bd.family.expenses.R
import bd.family.expenses.money.Taka

/** Problems the person must fix before saving, as string resource ids. */
data class EntryErrors(
  val amount: Int? = null,
  val category: Int? = null,
  val account: Int? = null,
  /** Transfers only: the destination account. */
  val destination: Int? = null,
) {
  val isEmpty: Boolean
    get() = amount == null && category == null && account == null && destination == null
}

sealed interface Validation {
  data class Valid(val amountPoisha: Long) : Validation

  data class Invalid(val errors: EntryErrors) : Validation
}

/** Pure validation, unit-tested on the JVM. The server validates again. */
fun validateEntry(amountText: String, categoryId: String?, accountId: String?): Validation {
  val amountError =
    when (Taka.parse(amountText)) {
      is Taka.Parsed.Ok -> null
      Taka.Parsed.BadFormat -> R.string.error_amount_format
      Taka.Parsed.OutOfRange -> R.string.error_amount_range
    }
  val errors =
    EntryErrors(
      amount = amountError,
      category = if (categoryId == null) R.string.error_choose_category else null,
      account = if (accountId == null) R.string.error_choose_account else null,
    )
  if (!errors.isEmpty) return Validation.Invalid(errors)
  return Validation.Valid((Taka.parse(amountText) as Taka.Parsed.Ok).poisha)
}

/** A transfer needs an amount and two different accounts. It has no category. */
fun validateTransfer(amountText: String, fromId: String?, toId: String?): Validation {
  val parsed = Taka.parse(amountText)
  val errors =
    EntryErrors(
      amount =
        when (parsed) {
          is Taka.Parsed.Ok -> null
          Taka.Parsed.BadFormat -> R.string.error_amount_format
          Taka.Parsed.OutOfRange -> R.string.error_amount_range
        },
      account = if (fromId == null) R.string.error_choose_from else null,
      destination =
        when {
          toId == null -> R.string.error_choose_to
          toId == fromId -> R.string.error_same_account
          else -> null
        },
    )
  if (!errors.isEmpty) return Validation.Invalid(errors)
  return Validation.Valid((parsed as Taka.Parsed.Ok).poisha)
}

/** Keep only characters that can be part of an amount (digits in either script, comma, dot). */
fun sanitiseAmountInput(input: String): String =
  input.filter { it.isDigit() || it in "০১২৩৪৫৬৭৮৯.," }.take(20)
