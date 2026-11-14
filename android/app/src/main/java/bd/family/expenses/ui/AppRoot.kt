package bd.family.expenses.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import bd.family.expenses.AppContainer
import bd.family.expenses.ui.entry.EditEntryScreen
import bd.family.expenses.ui.entry.EntryScreen
import bd.family.expenses.ui.entry.TransferScreen
import bd.family.expenses.ui.history.DetailScreen
import bd.family.expenses.ui.history.HistoryScreen
import bd.family.expenses.ui.home.BalancesScreen
import bd.family.expenses.ui.home.BudgetsScreen
import bd.family.expenses.ui.home.HomeScreen
import bd.family.expenses.ui.login.LoginScreen
import bd.family.expenses.ui.manage.AccountsAdminScreen
import bd.family.expenses.ui.manage.AllTransactionsScreen
import bd.family.expenses.ui.manage.CategoriesAdminScreen
import bd.family.expenses.ui.manage.ManageScreen
import bd.family.expenses.ui.manage.MembersAdminScreen
import bd.family.expenses.ui.manage.ReportsScreen
import bd.family.expenses.ui.manage.SummariesScreen
import bd.family.expenses.ui.settings.SettingsScreen
import kotlinx.serialization.Serializable

@Serializable data object HomeKey : NavKey

@Serializable data class EntryKey(val type: String) : NavKey

@Serializable data object HistoryKey : NavKey

@Serializable data object TransferKey : NavKey

@Serializable data class DetailKey(val clientId: String) : NavKey

@Serializable data class EditKey(val clientId: String) : NavKey

@Serializable data object BalancesKey : NavKey

@Serializable data object BudgetsKey : NavKey

@Serializable data object SettingsKey : NavKey

@Serializable data object ManageKey : NavKey

@Serializable data object ReportsKey : NavKey

@Serializable data object AllTransactionsKey : NavKey

@Serializable data object AccountsAdminKey : NavKey

@Serializable data object CategoriesAdminKey : NavKey

@Serializable data object MembersAdminKey : NavKey

@Serializable data object SummariesKey : NavKey

@Composable
fun AppRoot(container: AppContainer) {
  val profile by container.settings.profile.collectAsStateWithLifecycle()
  val loginRequired by container.session.loginRequired.collectAsStateWithLifecycle()
  Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
    if (profile == null || loginRequired) {
      LoginScreen(container, sessionEnded = loginRequired)
    } else {
      MainNavigation(container)
    }
  }
}

@Composable
private fun MainNavigation(container: AppContainer) {
  val backStack = rememberNavBackStack(HomeKey)
  NavDisplay(
    backStack = backStack,
    onBack = { backStack.removeLastOrNull() },
    entryProvider =
      entryProvider {
        entry<HomeKey> { HomeScreen(container, onNavigate = { backStack.add(it) }) }
        entry<EntryKey> { key -> EntryScreen(container, type = key.type, onDone = { backStack.removeLastOrNull() }) }
        entry<TransferKey> { TransferScreen(container, onDone = { backStack.removeLastOrNull() }) }
        entry<BudgetsKey> { BudgetsScreen(container, onBack = { backStack.removeLastOrNull() }) }
        entry<BalancesKey> { BalancesScreen(container, onBack = { backStack.removeLastOrNull() }) }
        entry<HistoryKey> {
          HistoryScreen(container, onOpen = { backStack.add(DetailKey(it)) }, onBack = { backStack.removeLastOrNull() })
        }
        entry<DetailKey> { key ->
          DetailScreen(
            container,
            key.clientId,
            onEdit = { backStack.add(EditKey(key.clientId)) },
            onBack = { backStack.removeLastOrNull() },
          )
        }
        entry<EditKey> { key -> EditEntryScreen(container, key.clientId, onDone = { backStack.removeLastOrNull() }) }
        entry<SettingsKey> { SettingsScreen(container, onBack = { backStack.removeLastOrNull() }) }
        // Management screens (admins). The server enforces permissions regardless.
        entry<ManageKey> { ManageScreen(onNavigate = { backStack.add(it) }, onBack = { backStack.removeLastOrNull() }) }
        entry<ReportsKey> { ReportsScreen(container, onBack = { backStack.removeLastOrNull() }) }
        entry<AllTransactionsKey> { AllTransactionsScreen(container, onBack = { backStack.removeLastOrNull() }) }
        entry<AccountsAdminKey> { AccountsAdminScreen(container, onBack = { backStack.removeLastOrNull() }) }
        entry<CategoriesAdminKey> { CategoriesAdminScreen(container, onBack = { backStack.removeLastOrNull() }) }
        entry<SummariesKey> { SummariesScreen(container, onBack = { backStack.removeLastOrNull() }) }
        entry<MembersAdminKey> { MembersAdminScreen(container, onBack = { backStack.removeLastOrNull() }) }
      },
  )
}
