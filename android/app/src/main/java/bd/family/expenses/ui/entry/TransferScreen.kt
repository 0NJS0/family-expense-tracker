package bd.family.expenses.ui.entry

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import bd.family.expenses.AppContainer
import bd.family.expenses.R
import bd.family.expenses.data.NewEntry
import bd.family.expenses.ui.common.ScreenBar
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.launch

/**
 * Moving money between the family's own accounts (e.g. cash into bKash).
 * A transfer is neither income nor expense; it is saved offline like any entry.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TransferScreen(container: AppContainer, onDone: () -> Unit) {
  val signedIn by container.settings.profile.collectAsStateWithLifecycle()
  // No early return after a composable call (see AGENTS.md): without a profile, show nothing.
  val profileNow = signedIn
  if (profileNow != null) TransferScreenContent(container, onDone, profileNow)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TransferScreenContent(container: AppContainer, onDone: () -> Unit, profile: bd.family.expenses.data.settings.Profile) {
  val householdId = profile.householdId
  val zone = ZoneId.of(profile?.timezone ?: "Asia/Dhaka")
  val accounts by remember(householdId) { container.database.reference().observeAccounts(householdId) }
    .collectAsStateWithLifecycle(emptyList())
  val usable = accounts.filter { !it.isArchived }
  val scope = rememberCoroutineScope()
  val scroll = rememberScrollState()

  val today = LocalDate.now(zone)
  var amountText by rememberSaveable { mutableStateOf("") }
  var fromId by rememberSaveable { mutableStateOf<String?>(null) }
  var toId by rememberSaveable { mutableStateOf<String?>(null) }
  var dateText by rememberSaveable { mutableStateOf(today.toString()) }
  var note by rememberSaveable { mutableStateOf("") }
  var errors by remember { mutableStateOf(EntryErrors()) }
  var saving by remember { mutableStateOf(false) }
  var saved by remember { mutableStateOf(false) }
  val date = LocalDate.parse(dateText)

  Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
    ScreenBar(stringResource(R.string.transfer_title), onBack = onDone)
    Column(
      modifier = Modifier.weight(1f).verticalScroll(scroll).padding(horizontal = 20.dp, vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      Text(stringResource(R.string.transfer_explain), style = MaterialTheme.typography.bodyLarge)
      if (usable.size < 2) {
        Text(stringResource(R.string.error_need_two_accounts), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyLarge)
      }

      AmountInput(amountText, errors.amount) { amountText = it }

      Section(stringResource(R.string.from_account), errors.account)
      FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        usable.forEach { a ->
          Choice(a.name, a.id == fromId, onClick = { fromId = a.id; if (toId == a.id) toId = null })
        }
      }

      Section(stringResource(R.string.to_account), errors.destination)
      FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // The source account is not offered as a destination.
        usable.filter { it.id != fromId }.forEach { a -> Choice(a.name, a.id == toId, onClick = { toId = a.id }) }
      }

      DateInput(date, today, zone) { dateText = it.toString() }

      OutlinedTextField(
        value = note,
        onValueChange = { note = it.take(500) },
        label = { Text(stringResource(R.string.note)) },
        modifier = Modifier.fillMaxWidth(),
      )
    }
    SaveBar(
      hasErrors = !errors.isEmpty,
      enabled = !saving && usable.size >= 2,
      onSave = {
        when (val v = validateTransfer(amountText, fromId, toId)) {
          is Validation.Invalid -> {
            errors = v.errors
            scope.launch { scroll.animateScrollTo(0) }
          }
          is Validation.Valid -> {
            errors = EntryErrors()
            saving = true
            scope.launch {
              container.entries.save(
                NewEntry(
                  type = "transfer",
                  amountPoisha = v.amountPoisha,
                  categoryId = null,
                  accountId = fromId!!,
                  date = date,
                  note = note,
                  attributedUserId = null,
                  destinationAccountId = toId!!,
                )
              )
              saving = false
              saved = true
            }
          }
        }
      },
    )
  }

  if (saved) SavedDialog(onDone)
}
