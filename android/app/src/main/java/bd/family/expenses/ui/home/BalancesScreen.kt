package bd.family.expenses.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import bd.family.expenses.AppContainer
import bd.family.expenses.R
import bd.family.expenses.data.local.PendingTotals
import bd.family.expenses.ui.common.LabeledRow
import bd.family.expenses.ui.common.ScreenBar
import bd.family.expenses.ui.common.formatDateTime
import bd.family.expenses.ui.common.money

/**
 * Account balances exactly as the server last reported them. Works offline
 * (cached at every sync); entries still on the phone are not added in, and the
 * screen says so.
 */
@Composable
fun BalancesScreen(container: AppContainer, onBack: () -> Unit) {
  val signedIn by container.settings.profile.collectAsStateWithLifecycle()
  // No early return after a composable call (see AGENTS.md): without a profile, show nothing.
  val profileNow = signedIn
  if (profileNow != null) BalancesScreenContent(container, onBack, profileNow)
}

@Composable
private fun BalancesScreenContent(container: AppContainer, onBack: () -> Unit, profile: bd.family.expenses.data.settings.Profile) {
  val revision by container.settings.revision.collectAsStateWithLifecycle()
  val householdId = profile.householdId
  val balances = remember(revision) { container.settings.cachedBalances }
  val lastSync = remember(revision) { container.settings.lastSyncAtMs }
  val pending by
    remember(householdId) { container.database.transactions().observePendingTotals(householdId) }
      .collectAsStateWithLifecycle(PendingTotals(0, 0, 0))

  Column(Modifier.fillMaxSize().safeDrawingPadding()) {
    ScreenBar(stringResource(R.string.balances_title), onBack = onBack)
    Column(
      Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      if (balances == null) {
        Text(stringResource(R.string.not_loaded_yet), style = MaterialTheme.typography.bodyLarge)
      } else {
        BalanceList(balances, lastSync, profile?.timezone ?: "Asia/Dhaka", pending.count)
      }
    }
  }
}

@Composable
private fun BalanceList(balances: bd.family.expenses.data.remote.BalancesDto, lastSync: Long, zone: String, pendingCount: Int) {
  Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
      Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
          balances.accounts.forEach { LabeledRow(it.name, money(it.balancePoisha)) }
          HorizontalDivider()
          LabeledRow(stringResource(R.string.total_balance), money(balances.totalPoisha))
        }
      }
      if (lastSync > 0) {
        Text(
          stringResource(R.string.updated_at, formatDateTime(lastSync, zone)),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      if (pendingCount > 0) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
          Text(
            "⏳ " + stringResource(R.string.balances_pending_note, pendingCount),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(16.dp),
          )
        }
      }
  }
}
