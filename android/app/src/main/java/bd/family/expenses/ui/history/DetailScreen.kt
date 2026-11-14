package bd.family.expenses.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import bd.family.expenses.AppContainer
import bd.family.expenses.R
import bd.family.expenses.data.ChangeRepository
import bd.family.expenses.data.local.SyncState
import bd.family.expenses.data.local.TransactionEntity
import bd.family.expenses.ui.common.BigButton
import bd.family.expenses.ui.common.LabeledRow
import bd.family.expenses.ui.common.ScreenBar
import bd.family.expenses.ui.common.formatDate
import bd.family.expenses.ui.common.isBangla
import bd.family.expenses.ui.common.money
import bd.family.expenses.ui.entry.Choice
import bd.family.expenses.ui.theme.TouchTarget
import java.time.OffsetDateTime
import kotlinx.coroutines.launch

/**
 * One entry: its details, its upload state, and what the person can do about
 * it (correct, void, or settle a problem). Everything works offline.
 */
@Composable
fun DetailScreen(container: AppContainer, clientId: String, onEdit: () -> Unit, onBack: () -> Unit) {
  val signedIn by container.settings.profile.collectAsStateWithLifecycle()
  // No early return after a composable call (see AGENTS.md): without a profile, show nothing.
  val currentNow = signedIn
  if (currentNow != null) DetailScreenContent(container, clientId, onEdit, onBack, currentNow)
}

@Composable
private fun DetailScreenContent(container: AppContainer, clientId: String, onEdit: () -> Unit, onBack: () -> Unit, current: bd.family.expenses.data.settings.Profile) {
  val item by remember(clientId) { container.database.transactions().observeOne(clientId) }.collectAsStateWithLifecycle(null)

  val loaded = item
  if (loaded == null) {
    Column(Modifier.fillMaxSize().safeDrawingPadding()) { ScreenBar(stringResource(R.string.entry_details), onBack = onBack) }
  } else {
    DetailContent(container, current, loaded, clientId, onEdit, onBack)
  }
}

@Composable
private fun DetailContent(
  container: AppContainer,
  current: bd.family.expenses.data.settings.Profile,
  loaded: bd.family.expenses.data.local.TransactionWithChange,
  clientId: String,
  onEdit: () -> Unit,
  onBack: () -> Unit,
) {
  val categories by remember(current.householdId) { container.database.reference().observeCategories(current.householdId) }
    .collectAsStateWithLifecycle(emptyList())
  val accounts by remember(current.householdId) { container.database.reference().observeAccounts(current.householdId) }
    .collectAsStateWithLifecycle(emptyList())
  val scope = rememberCoroutineScope()
  var askVoid by remember { mutableStateOf(false) }
  var askDiscard by remember { mutableStateOf(false) }
  val tx = loaded.tx
  val change = loaded.change
  val shown = loaded.shown
  val bangla = isBangla()
  val categoryName = { id: String? -> categories.firstOrNull { it.id == id }?.let { if (bangla) it.nameBn else it.nameEn } ?: "" }
  val accountName = { id: String? -> accounts.firstOrNull { it.id == id }?.name ?: "" }
  val (status, problem) = statusText(loaded)
  val allowed = ChangeRepository.canChange(tx, current)
  val act: (suspend () -> Unit) -> Unit = { block -> scope.launch { block() } }

  Column(Modifier.fillMaxSize().safeDrawingPadding()) {
    ScreenBar(stringResource(R.string.entry_details), onBack = onBack)
    Column(
      Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      EntryCard(shown, categoryName, accountName, current.timezone)
      Card(
        colors =
          CardDefaults.cardColors(
            containerColor = if (problem) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant
          ),
        modifier = Modifier.fillMaxWidth(),
      ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
          Text(status, style = MaterialTheme.typography.titleMedium)
          val error = change?.lastError ?: tx.lastError.takeIf { tx.syncState == SyncState.FAILED || tx.syncState == SyncState.CONFLICT }
          if (problem) error?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
        }
      }

      ReceiptSection(
        container,
        clientId,
        canAdd = allowed && !shown.voided && (tx.syncState == SyncState.PENDING || tx.syncState == SyncState.SYNCED),
      )

      when {
        // Someone else changed (or voided) the entry before this change arrived.
        change?.state == SyncState.CONFLICT -> {
          val server = ChangeRepository.serverCopy(change)
          Text(stringResource(R.string.conflict_explain), style = MaterialTheme.typography.bodyLarge)
          if (server != null) {
            Text(stringResource(R.string.conflict_server_now), style = MaterialTheme.typography.titleMedium)
            if (server.voidedAt != null) {
              Text(stringResource(R.string.conflict_voided_elsewhere), style = MaterialTheme.typography.bodyLarge)
            } else {
              EntryCard(tx, categoryName, accountName, current.timezone)
            }
            Text(stringResource(R.string.conflict_your_change), style = MaterialTheme.typography.titleMedium)
            EntryCard(tx.withChangeShown(change), categoryName, accountName, current.timezone)
            if (server.voidedAt == null) {
              BigButton(stringResource(R.string.conflict_reapply), onClick = { act { container.changes.reapply(clientId) } })
            }
          }
          OutlinedAction(stringResource(R.string.conflict_discard)) { act { container.changes.discardChange(clientId) } }
        }
        change?.state == SyncState.FAILED -> {
          if (!change.isVoid) BigButton(stringResource(R.string.correct_again), onClick = onEdit)
          OutlinedAction(stringResource(R.string.discard_change)) { act { container.changes.discardChange(clientId) } }
        }
        // A void waiting to be sent can still be taken back.
        change != null && change.isVoid -> {
          OutlinedAction(stringResource(R.string.undo_void)) { act { container.changes.discardChange(clientId) } }
        }
        // A new entry the server would not accept: correct and resend, or drop it.
        tx.syncState == SyncState.FAILED -> {
          BigButton(stringResource(R.string.fix_and_resend), onClick = onEdit)
          OutlinedAction(stringResource(R.string.discard_entry)) { askDiscard = true }
        }
        tx.syncState == SyncState.CONFLICT -> {
          Text(stringResource(R.string.create_conflict_explain), style = MaterialTheme.typography.bodyLarge)
          BigButton(stringResource(R.string.resend_as_new), onClick = { act { container.changes.resendAsNew(clientId); onBack() } })
          OutlinedAction(stringResource(R.string.discard_entry)) { askDiscard = true }
        }
        allowed && !tx.voided -> {
          BigButton(stringResource(R.string.correct), onClick = onEdit)
          OutlinedAction(stringResource(R.string.void_entry)) { askVoid = true }
        }
        !allowed -> Text(stringResource(R.string.only_own_entries), style = MaterialTheme.typography.bodyLarge)
      }
    }
  }

  if (askVoid) VoidDialog(onDismiss = { askVoid = false }) { reason ->
    askVoid = false
    act { container.changes.void(clientId, reason) }
  }
  if (askDiscard) {
    AlertDialog(
      onDismissRequest = { askDiscard = false },
      text = { Text(stringResource(R.string.discard_entry_confirm), style = MaterialTheme.typography.bodyLarge) },
      confirmButton = {
        TextButton(onClick = {
          askDiscard = false
          act {
            container.changes.discardRejected(clientId)
            onBack()
          }
        }, modifier = Modifier.heightIn(min = TouchTarget)) { Text(stringResource(R.string.discard_entry)) }
      },
      dismissButton = {
        TextButton(onClick = { askDiscard = false }, modifier = Modifier.heightIn(min = TouchTarget)) { Text(stringResource(R.string.cancel)) }
      },
    )
  }
}

private fun TransactionEntity.withChangeShown(change: bd.family.expenses.data.local.ChangeEntity) =
  bd.family.expenses.data.local.TransactionWithChange(this, change).shown

@Composable
private fun EntryCard(row: TransactionEntity, categoryName: (String?) -> String, accountName: (String?) -> String, zone: String) {
  Card(Modifier.fillMaxWidth()) {
    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      val type =
        stringResource(
          when (row.type) {
            "income" -> R.string.income
            "transfer" -> R.string.transfer
            else -> R.string.expense
          }
        )
      Text(
        money(if (row.type == "expense") -row.amountPoisha else row.amountPoisha),
        style = MaterialTheme.typography.headlineMedium,
      )
      if (row.voided) Text(stringResource(R.string.voided), style = MaterialTheme.typography.titleMedium)
      LabeledRow(stringResource(R.string.kind_label), type)
      if (row.type == "transfer") {
        LabeledRow(stringResource(R.string.from_account), accountName(row.accountId))
        LabeledRow(stringResource(R.string.to_account), accountName(row.destinationAccountId))
      } else {
        LabeledRow(stringResource(R.string.category), categoryName(row.categoryId))
        LabeledRow(stringResource(if (row.type == "income") R.string.income_account else R.string.account), accountName(row.accountId))
      }
      LabeledRow(stringResource(R.string.date), formatDate(OffsetDateTime.parse(row.occurredAt).toInstant().toEpochMilli(), zone))
      row.note?.let { LabeledRow(stringResource(R.string.note), it) }
    }
  }
}

@Composable
private fun OutlinedAction(text: String, onClick: () -> Unit) {
  OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = TouchTarget)) {
    Text(text, style = MaterialTheme.typography.titleMedium)
  }
}

/** Ask why, in a tap: the reason is kept with the voided entry on the server. */
@Composable
private fun VoidDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
  val mistake = stringResource(R.string.void_reason_mistake)
  val twice = stringResource(R.string.void_reason_twice)
  var reason by remember { mutableStateOf(mistake) }
  var other by remember { mutableStateOf(false) }
  var otherText by remember { mutableStateOf("") }
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(R.string.void_confirm_title)) },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.void_confirm_text), style = MaterialTheme.typography.bodyLarge)
        Choice(mistake, !other && reason == mistake, onClick = { other = false; reason = mistake })
        Choice(twice, !other && reason == twice, onClick = { other = false; reason = twice })
        Choice(stringResource(R.string.void_reason_other), other, onClick = { other = true })
        if (other) {
          OutlinedTextField(
            value = otherText,
            onValueChange = { otherText = it.take(500) },
            label = { Text(stringResource(R.string.void_reason_label)) },
            modifier = Modifier.fillMaxWidth(),
          )
        }
      }
    },
    confirmButton = {
      TextButton(
        onClick = { onConfirm(if (other) otherText.ifBlank { reason } else reason) },
        modifier = Modifier.heightIn(min = TouchTarget),
      ) { Text(stringResource(R.string.void_entry), style = MaterialTheme.typography.titleMedium) }
    },
    dismissButton = {
      TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = TouchTarget)) {
        Text(stringResource(R.string.keep_entry), style = MaterialTheme.typography.titleMedium)
      }
    },
  )
}
