package bd.family.expenses.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavKey
import bd.family.expenses.AppContainer
import bd.family.expenses.R
import bd.family.expenses.data.local.PendingTotals
import bd.family.expenses.data.local.SyncState
import bd.family.expenses.ui.common.categoryIcon
import bd.family.expenses.ui.common.isBangla
import bd.family.expenses.ui.BalancesKey
import bd.family.expenses.ui.BudgetsKey
import bd.family.expenses.ui.DetailKey
import bd.family.expenses.ui.EntryKey
import bd.family.expenses.ui.TransferKey
import bd.family.expenses.ui.HistoryKey
import bd.family.expenses.ui.ManageKey
import bd.family.expenses.ui.SettingsKey
import bd.family.expenses.ui.common.BigButton
import bd.family.expenses.ui.common.LabeledRow
import bd.family.expenses.ui.common.formatDateTime
import bd.family.expenses.ui.common.money
import bd.family.expenses.ui.theme.TouchTarget

@Composable
fun HomeScreen(container: AppContainer, onNavigate: (NavKey) -> Unit) {
  val signedIn by container.settings.profile.collectAsStateWithLifecycle()
  // No early return after a composable call (see AGENTS.md): without a profile, show nothing.
  val profileNow = signedIn
  if (profileNow != null) HomeScreenContent(container, onNavigate, profileNow)
}

@Composable
private fun HomeScreenContent(container: AppContainer, onNavigate: (NavKey) -> Unit, profile: bd.family.expenses.data.settings.Profile) {
  val revision by container.settings.revision.collectAsStateWithLifecycle()
  val householdId = profile.householdId
  val pending by
    remember(householdId) { container.database.transactions().observePendingTotals(householdId) }
      .collectAsStateWithLifecycle(PendingTotals(0, 0, 0))
  val syncing by container.scheduler.isRunning.collectAsStateWithLifecycle(false)
  // Re-read cached server figures whenever a sync stores new ones.
  val summary = remember(revision) { container.settings.cachedSummary }
  val today = remember(revision) { container.settings.cachedTodaySummary }
  val budgets = remember(revision) { container.settings.cachedBudgets }
  val due = remember(revision) { container.settings.cachedDue.orEmpty() }
  val recent by
    remember(householdId) { container.database.transactions().observeRecentWithChanges(householdId, 3) }
      .collectAsStateWithLifecycle(emptyList())
  val categories by
    remember(householdId) { container.database.reference().observeCategories(householdId) }
      .collectAsStateWithLifecycle(emptyList())
  val lastSync = remember(revision) { container.settings.lastSyncAtMs }
  val accounts by
    remember(householdId) { container.database.reference().observeAccounts(householdId) }
      .collectAsStateWithLifecycle(emptyList())
  val accountNames = accounts.associate { it.id to it.name }

  Column(
    modifier = Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    Text(profile?.householdName ?: "", style = MaterialTheme.typography.headlineMedium)

    Card(modifier = Modifier.fillMaxWidth()) {
      Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.this_month), style = MaterialTheme.typography.titleLarge)
        if (summary == null) {
          Text(stringResource(R.string.not_loaded_yet), style = MaterialTheme.typography.bodyLarge)
        } else {
          // Server figures only; entries still on the phone are shown separately below.
          today?.let { LabeledRow(stringResource(R.string.spent_today), money(it.expensePoisha)) }
          LabeledRow(stringResource(R.string.spent), money(summary.expensePoisha))
          LabeledRow(stringResource(R.string.received), money(summary.incomePoisha))
          if (lastSync > 0) {
            Text(
              stringResource(R.string.updated_at, formatDateTime(lastSync, profile?.timezone ?: "Asia/Dhaka")),
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
      }
    }

    SyncBanner(pending, syncing)

    BigButton(stringResource(R.string.add_expense), onClick = { onNavigate(EntryKey("expense")) })
    OutlinedButton(
      onClick = { onNavigate(EntryKey("income")) },
      modifier = Modifier.fillMaxWidth().heightIn(min = TouchTarget),
    ) {
      Text(stringResource(R.string.add_income), style = MaterialTheme.typography.titleMedium)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
      OutlinedButton(onClick = { onNavigate(TransferKey) }, modifier = Modifier.weight(1f).heightIn(min = TouchTarget)) {
        Text("↔  " + stringResource(R.string.transfer), style = MaterialTheme.typography.titleMedium)
      }
      OutlinedButton(onClick = { onNavigate(BalancesKey) }, modifier = Modifier.weight(1f).heightIn(min = TouchTarget)) {
        Text(stringResource(R.string.balances_short), style = MaterialTheme.typography.titleMedium)
      }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
      OutlinedButton(onClick = { onNavigate(HistoryKey) }, modifier = Modifier.weight(1f).heightIn(min = TouchTarget)) {
        Text(stringResource(R.string.history), style = MaterialTheme.typography.titleMedium)
      }
      OutlinedButton(onClick = { onNavigate(SettingsKey) }, modifier = Modifier.weight(1f).heightIn(min = TouchTarget)) {
        Text(stringResource(R.string.settings), style = MaterialTheme.typography.titleMedium)
      }
    }

    if (profile?.isAdmin == true) {
      // Admins only: the dashboard's tools on the phone. Members never see this.
      OutlinedButton(onClick = { onNavigate(ManageKey) }, modifier = Modifier.fillMaxWidth().heightIn(min = TouchTarget)) {
        Text("⚙  " + stringResource(R.string.manage), style = MaterialTheme.typography.titleMedium)
      }
    }

    // Below the buttons: adding an entry must never need scrolling.
    DueCard(container, due, canResolve = profile?.role != "viewer", zone = profile?.timezone ?: "Asia/Dhaka")
    budgets?.let { BudgetCard(it, onOpen = { onNavigate(BudgetsKey) }) }

    if (recent.isNotEmpty()) {
      Text(stringResource(R.string.recent_entries), style = MaterialTheme.typography.titleLarge)
      val byId = categories.associateBy { it.id }
      recent.forEach { item ->
        // Shown with any waiting correction applied; its state counts as "not sent yet".
        val row = item.shown
        val category = row.categoryId?.let { byId[it] }
        RecentRow(
          icon = if (row.type == "transfer") "↔" else category?.let { categoryIcon(it.nameEn, it.kind) } ?: "🧾",
          name =
            if (row.type == "transfer") transferLabel(accountNames, row.accountId, row.destinationAccountId)
            else category?.let { if (isBangla()) it.nameBn else it.nameEn } ?: "",
          amount = money(if (row.type == "expense") -row.amountPoisha else row.amountPoisha),
          sent = row.syncState == SyncState.SYNCED && item.change == null,
          problem =
            row.syncState == SyncState.FAILED || row.syncState == SyncState.CONFLICT ||
              item.change?.state == SyncState.FAILED || item.change?.state == SyncState.CONFLICT,
          onClick = { onNavigate(DetailKey(row.clientId)) },
        )
      }
      TextButton(onClick = { onNavigate(HistoryKey) }, modifier = Modifier.heightIn(min = TouchTarget)) {
        Text(stringResource(R.string.see_all), style = MaterialTheme.typography.titleMedium)
      }
    }
  }
}

/** "Cash → bKash" for a transfer row. */
fun transferLabel(names: Map<String, String>, from: String, to: String?): String =
  (names[from] ?: "?") + " → " + (to?.let { names[it] } ?: "?")

@Composable
private fun RecentRow(icon: String, name: String, amount: String, sent: Boolean, problem: Boolean, onClick: () -> Unit) {
  val status =
    when {
      problem -> "⚠"
      sent -> "✓"
      else -> "⏳"
    }
  val statusText =
    stringResource(
      when {
        problem -> R.string.state_failed
        sent -> R.string.state_synced
        else -> R.string.state_pending
      }
    )
  Card(
    modifier =
      Modifier.fillMaxWidth().clickable(onClick = onClick).semantics(mergeDescendants = true) {
        contentDescription = "$name, $amount, $statusText"
      },
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
  ) {
    Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
      Text(icon, style = MaterialTheme.typography.headlineSmall)
      Spacer(Modifier.width(12.dp))
      Text(name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
      Text(amount, style = MaterialTheme.typography.titleMedium)
      Spacer(Modifier.width(10.dp))
      Text(status, style = MaterialTheme.typography.titleMedium)
    }
  }
}

@Composable
private fun SyncBanner(pending: PendingTotals, syncing: Boolean) {
  val waiting = pending.count > 0
  Card(
    modifier = Modifier.fillMaxWidth(),
    colors =
      CardDefaults.cardColors(
        containerColor =
          if (waiting) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant
      ),
  ) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
      // Text and symbol carry the meaning, not colour alone.
      Text(
        if (waiting) "⏳ " + stringResource(R.string.waiting_to_send, pending.count) else "✓ " + stringResource(R.string.all_sent),
        style = MaterialTheme.typography.titleMedium,
      )
      if (pending.expensePoisha > 0) {
        Text(stringResource(R.string.waiting_expense, money(pending.expensePoisha)), style = MaterialTheme.typography.bodyLarge)
      }
      if (syncing) Text(stringResource(R.string.syncing), style = MaterialTheme.typography.bodyMedium)
    }
  }
}
