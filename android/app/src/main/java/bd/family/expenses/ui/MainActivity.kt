package bd.family.expenses.ui

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import bd.family.expenses.FamilyExpensesApp
import bd.family.expenses.ui.theme.FamilyExpensesTheme

/** AppCompatActivity so the in-app language switch works on every Android version. */
class MainActivity : AppCompatActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    // Bangla by default, whatever the phone's system language; English is
    // chosen in Settings. AppCompat requires this call after Activity.onCreate
    // (not in Application.onCreate, where it is silently ignored). On the very
    // first launch it recreates the activity once to apply the language.
    if (AppCompatDelegate.getApplicationLocales().isEmpty) {
      AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("bn"))
    }
    enableEdgeToEdge()
    val container = (application as FamilyExpensesApp).container
    setContent { FamilyExpensesTheme { AppRoot(container) } }
  }
}
