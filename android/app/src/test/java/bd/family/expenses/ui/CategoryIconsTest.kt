package bd.family.expenses.ui

import bd.family.expenses.data.remote.CategoryDto
import bd.family.expenses.ui.common.categoryIcon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class CategoryIconsTest {
  // The built-in categories created by the backend (backend/app/services/households.py).
  private val builtIn =
    listOf(
      "Groceries", "Food & dining", "Transport", "Utilities", "Rent", "Health & medicine", "Education",
      "Mobile & internet", "Clothing", "Household items", "Gifts & donations", "Family support",
    ) to listOf("Salary", "Pension", "Business", "Rental income", "Gifts received", "Interest & profit")

  @Test
  fun everyBuiltInCategoryHasItsOwnIcon() {
    builtIn.first.forEach { assertNotEquals(it, "🧾", categoryIcon(it, "expense")) }
    builtIn.second.forEach { assertNotEquals(it, "💰", categoryIcon(it, "income")) }
  }

  @Test
  fun matchingIgnoresCaseAndSpacing() {
    assertEquals("🛒", categoryIcon("  GROCERIES ", "expense"))
  }

  @Test
  fun customCategoriesGetAGenericIconByKind() {
    val custom = CategoryDto("c", "expense", "Pets", "পোষা প্রাণী", false)
    assertEquals("🧾", categoryIcon(custom.nameEn, custom.kind))
    assertEquals("💰", categoryIcon("Lottery", "income"))
  }
}
