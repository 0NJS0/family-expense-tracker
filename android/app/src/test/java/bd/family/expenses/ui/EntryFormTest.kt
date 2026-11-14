package bd.family.expenses.ui

import bd.family.expenses.R
import bd.family.expenses.ui.entry.Validation
import bd.family.expenses.ui.entry.sanitiseAmountInput
import bd.family.expenses.ui.entry.validateEntry
import bd.family.expenses.ui.entry.validateTransfer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EntryFormTest {
  @Test
  fun validEntry() {
    assertEquals(Validation.Valid(12050), validateEntry("১২০.৫০", "cat", "acc"))
  }

  @Test
  fun reportsEveryProblemAtOnce() {
    val result = validateEntry("12,50", null, null) as Validation.Invalid
    assertEquals(R.string.error_amount_format, result.errors.amount)
    assertEquals(R.string.error_choose_category, result.errors.category)
    assertEquals(R.string.error_choose_account, result.errors.account)
  }

  @Test
  fun zeroIsOutOfRange() {
    val result = validateEntry("0", "cat", "acc") as Validation.Invalid
    assertEquals(R.string.error_amount_range, result.errors.amount)
  }

  @Test
  fun inputKeepsOnlyAmountCharacters() {
    assertEquals("1,250.50", sanitiseAmountInput("৳ 1,250.50 tk"))
    assertEquals("১২০", sanitiseAmountInput("১২০"))
    assertTrue(sanitiseAmountInput("1".repeat(40)).length <= 20)
  }

  @Test
  fun validTransfer() {
    assertEquals(Validation.Valid(50000), validateTransfer("৫০০", "cash", "bkash"))
  }

  @Test
  fun transferNeedsTwoDifferentAccounts() {
    val missing = validateTransfer("500", null, null) as Validation.Invalid
    assertEquals(R.string.error_choose_from, missing.errors.account)
    assertEquals(R.string.error_choose_to, missing.errors.destination)
    val same = validateTransfer("500", "cash", "cash") as Validation.Invalid
    assertEquals(R.string.error_same_account, same.errors.destination)
    assertEquals(null, same.errors.amount)
  }

  @Test
  fun transferAmountIsValidatedLikeAnyEntry() {
    val bad = validateTransfer("0", "cash", "bkash") as Validation.Invalid
    assertEquals(R.string.error_amount_range, bad.errors.amount)
  }
}
