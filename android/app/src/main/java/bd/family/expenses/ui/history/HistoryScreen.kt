package bd.family.expenses.ui.history

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import bd.family.expenses.AppContainer
import bd.family.expenses.R
import bd.family.expenses.data.local.SyncState
import bd.family.expenses.data.local.TransactionWithChange
import bd.family.expenses.ui.common.ScreenBar
import bd.family.expenses.ui.common.formatDate
import bd.family.expenses.ui.common.isBangla
import bd.family.expenses.ui.common.money
import bd.family.expenses.ui.home.transferLabel

@Composable
fun HistoryScreen(container: AppContainer, onOpen: (String) -> Unit, onBack: () -> Unit) {
  val signedIn by container.settings.profile.collectAsStateWithLifecycle()
  // No early return after a composable call (see AGENTS.md): without a profile, show nothing.
  val profileNow = signedIn
  if (profileNow != null) HistoryScreenContent(container, onOpen, onBack, profileNow)
}

@Composable
private fun HistoryScreenContent(container: AppContainer, onOpen: (String) -> Unit, onBack: () -> Unit, profile: bd.family.expenses.data.settings.Profile) {
  val householdId = profile.householdId
  val zone = profile?.timezone ?: "Asia/Dhaka"
  val rows by remember(householdId) { container.database.transactions().observeRecentWithChanges(householdId, 200) }
    .collectAsStateWithLifecycle(emptyList())
  val categories by remember(householdId) { container.database.reference().observeCategories(householdId) }
    .collectAsStateWithLifecycle(emptyList())
  val accounts by remember(householdId) { container.database.reference().observeAccounts(householdId) }
    .collectAsStateWithLifecycle(emptyList())
  val names = categories.associate { it.id to if (isBangla()) it.nameBn else it.nameEn }
  val accountNames = accounts.associate { it.id to it.name }

  Column(Modifier.fillMaxSize().safeDrawingPadding()) {
    ScreenBar(stringResource(R.string.history), onBack = onBack)
    if (rows.isEmpty()) {
      Text(stringResource(R.string.history_empty), modifier = Modifier.padding(20.dp), style = MaterialTheme.typography.bodyLarge)
    } else {
      Text(
        stringResource(R.string.history_tap_hint),
        modifier = Modifier.padding(horizontal = 20.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    LazyColumn(
      modifier = Modifier.fillMaxSize(),
      verticalArrangement = Arrangement.spacedBy(10.dp),
      contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
    ) {
      items(rows, key = { it.tx.clientId }) { item ->
        val shown = item.shown
        val label =
          if (shown.type == "transfer") "↔ " + transferLabel(accountNames, shown.accountId, shown.destinationAccountId)
          else names[shown.categoryId]
        HistoryRow(item, label, zone, onClick = { onOpen(item.tx.clientId) })
      }
    }
  }
}

/** Upload state in symbol and words, for the entry and any waiting change. */
@Composable
fun statusText(item: TransactionWithChange): Pair<String, Boolean> {
  val change = item.change
  return when {
    change?.state == SyncState.CONFLICT -> "⚠ " + stringResource(R.string.state_change_conflict) to true
    change?.state == SyncState.FAILED -> "⚠ " + stringResource(R.string.state_change_failed) to true
    change != null && change.isVoid -> "⏳ " + stringResource(R.string.state_void_pending) to false
    change != null -> "⏳ " + stringResource(R.string.state_change_pending) to false
    else ->
      when (item.tx.syncState) {
        SyncState.PENDING -> "⏳ " + stringResource(R.string.state_pending) to false
        SyncState.SYNCED -> "✓ " + stringResource(R.string.state_synced) to false
        SyncState.FAILED -> "⚠ " + stringResource(R.string.state_failed) to true
        SyncState.CONFLICT -> "⚠ " + stringResource(R.string.state_conflict) to true
        SyncState.DISCARDED -> stringResource(R.string.state_discarded) to false
      }
  }
}

@Composable
private fun HistoryRow(item: TransactionWithChange, categoryName: String?, zone: String, onClick: () -> Unit) {
  val row = item.shown
  val (status, problem) = statusText(item)
  val typeLabel =
    stringResource(
      when (row.type) {
        "income" -> R.string.income
        "transfer" -> R.string.transfer
        else -> R.string.expense
      }
    )
  val amount = money(if (row.type == "expense") -row.amountPoisha else row.amountPoisha)
  val date = formatDate(row.occurredAtEpochMs, zone)
  val error = item.change?.lastError ?: item.tx.lastError
  Card(
    modifier =
      Modifier.fillMaxWidth().clickable(onClick = onClick).semantics(mergeDescendants = true) {
        contentDescription = "$date, ${categoryName ?: typeLabel}, $amount, $status"
      },
    colors =
      CardDefaults.cardColors(
        containerColor = if (problem) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surface
      ),
  ) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(categoryName ?: typeLabel, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        // A waiting void is shown struck through (and said in words below).
        Text(
          amount,
          style = MaterialTheme.typography.titleLarge,
          textDecoration = if (row.voided) TextDecoration.LineThrough else null,
        )
      }
      Text(date, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
      row.note?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
      Text(status, style = MaterialTheme.typography.labelMedium)
      if (problem) error?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    }
  }
}
