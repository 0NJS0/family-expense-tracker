package bd.family.expenses.ui.manage

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavKey
import bd.family.expenses.R
import bd.family.expenses.ui.AccountsAdminKey
import bd.family.expenses.ui.AllTransactionsKey
import bd.family.expenses.ui.CategoriesAdminKey
import bd.family.expenses.ui.MembersAdminKey
import bd.family.expenses.ui.ReportsKey
import bd.family.expenses.ui.SummariesKey
import bd.family.expenses.ui.common.ScreenBar

/** Admin hub: the dashboard's tools, on the phone. */
@Composable
fun ManageScreen(onNavigate: (NavKey) -> Unit, onBack: () -> Unit) {
  Column(Modifier.fillMaxSize().safeDrawingPadding()) {
    ScreenBar(stringResource(R.string.manage), onBack = onBack)
    Column(
      Modifier.verticalScroll(rememberScrollState()).padding(20.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Tile("📊", stringResource(R.string.reports)) { onNavigate(ReportsKey) }
      Tile("🤖", stringResource(R.string.ai_summaries)) { onNavigate(SummariesKey) }
      Tile("📋", stringResource(R.string.all_transactions)) { onNavigate(AllTransactionsKey) }
      Tile("👛", stringResource(R.string.accounts_title)) { onNavigate(AccountsAdminKey) }
      Tile("🏷️", stringResource(R.string.categories_title)) { onNavigate(CategoriesAdminKey) }
      Tile("👪", stringResource(R.string.members_title)) { onNavigate(MembersAdminKey) }
    }
  }
}

@Composable
private fun Tile(icon: String, label: String, onClick: () -> Unit) {
  OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp)) {
    androidx.compose.foundation.layout.Row(
      Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(icon, style = MaterialTheme.typography.headlineSmall)
      Spacer(Modifier.width(16.dp))
      Text(label, style = MaterialTheme.typography.titleLarge)
    }
  }
}
