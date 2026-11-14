package bd.family.expenses.money

import org.junit.Assert.assertEquals
import org.junit.Test

/** Same cases as backend/tests/test_money.py, so phone and server agree exactly. */
class TakaTest {
  private fun ok(text: String) = (Taka.parse(text) as Taka.Parsed.Ok).poisha

  @Test
  fun parsesExactly() {
    assertEquals(100L, ok("1"))
    assertEquals(12050L, ok("120.5"))
    assertEquals(12050L, ok("120.50"))
    assertEquals(1L, ok("0.01"))
    assertEquals(7525L, ok("  75.25 "))
    assertEquals(125075L, ok("1,250.75"))
    assertEquals(125_000_000L, ok("12,50,000"))
    assertEquals(12050L, ok("১২০.৫০"))
    assertEquals(Taka.MAX_TRANSACTION_POISHA, ok("1000000000"))
  }

  @Test
  fun rejectsAmbiguousOrMalformedInput() {
    listOf("", "abc", "-5", "+5", "1.234", "1e3", "12,50", "1,2,3", ".5", "5.", "NaN", "1 000", "0x10")
      .forEach { assertEquals("input '$it'", Taka.Parsed.BadFormat, Taka.parse(it)) }
  }

  @Test
  fun rejectsOutOfRange() {
    listOf("0", "0.00", "1000000000.01", "999999999999999").forEach {
      assertEquals("input '$it'", Taka.Parsed.OutOfRange, Taka.parse(it))
    }
  }

  @Test
  fun noFloatArtefacts() {
    assertEquals(ok("0.30"), ok("0.10") + ok("0.20"))
    assertEquals("0.29", Taka.toInputText(29))
  }

  @Test
  fun formatsWithSouthAsianGrouping() {
    assertEquals("৳0.00", Taka.format(0, bangla = false))
    assertEquals("৳0.05", Taka.format(5, bangla = false))
    assertEquals("৳120.50", Taka.format(12050, bangla = false))
    assertEquals("৳1,25,000.00", Taka.format(12_500_000, bangla = false))
    assertEquals("৳1,00,00,00,000.00", Taka.format(Taka.MAX_TRANSACTION_POISHA, bangla = false))
    assertEquals("−৳12.50", Taka.format(-1250, bangla = false))
    assertEquals("+৳5.00", Taka.format(500, bangla = false, showSign = true))
  }

  @Test
  fun formatsInBanglaDigits() {
    assertEquals("৳১,২৫,০০০.৫০", Taka.format(12_500_050, bangla = true))
  }

  @Test
  fun parsesSignedStartingBalances() {
    assertEquals(0L, Taka.parseSigned("0"))
    assertEquals(-125075L, Taka.parseSigned("-1,250.75"))
    assertEquals(-50000L, Taka.parseSigned("−৫০০"))
    assertEquals(Taka.MAX_ABS_OPENING_POISHA, Taka.parseSigned("100000000000"))
    listOf("", "abc", "1.234", "12,50", "--5", "100000000000.01").forEach {
      assertEquals("input '$it'", null, Taka.parseSigned(it))
    }
  }
}
