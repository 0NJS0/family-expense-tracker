package bd.family.expenses.ui.common

/**
 * A recognisable picture for each built-in category, so the right button can
 * be found before reading. Matched on the English name, which does not change
 * with the display language. Custom categories get a generic icon.
 */
fun categoryIcon(nameEn: String, kind: String): String =
  when (nameEn.trim().lowercase()) {
    "groceries" -> "🛒"
    "food & dining" -> "🍛"
    "transport" -> "🚌"
    "utilities" -> "💡"
    "rent" -> "🏠"
    "health & medicine" -> "💊"
    "education" -> "📚"
    "mobile & internet" -> "📱"
    "clothing" -> "👕"
    "household items" -> "🧺"
    "gifts & donations", "gifts received" -> "🎁"
    "family support" -> "👪"
    "salary" -> "💼"
    "pension" -> "🏦"
    "business" -> "🏪"
    "rental income" -> "🏘️"
    "interest & profit" -> "📈"
    else -> if (kind == "income") "💰" else "🧾"
  }
