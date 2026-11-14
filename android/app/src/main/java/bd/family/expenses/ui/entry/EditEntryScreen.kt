package bd.family.expenses.ui.entry

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import bd.family.expenses.data.EntryEdit
import bd.family.expenses.data.local.TransactionEntity
import bd.family.expenses.money.Taka
import bd.family.expenses.ui.common.ScreenBar
import bd.family.expenses.ui.common.categoryIcon
import bd.family.expenses.ui.common.isBangla
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.launch

/** Correct an entry: the same form as entering it, filled in with what is shown now. */
@Composable
fun EditEntryScreen(container: AppContainer, clientId: String, onDone: () -> Unit) {
  val item by remember(clientId) { container.database.transactions().observeOne(clientId) }.collectAsStateWithLifecycle(null)
  val profile by container.settings.profile.collectAsStateWithLifecycle()
  val shown = item?.shown
  val signedIn = profile
  Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
    ScreenBar(stringResource(R.string.correct_title), onBack = onDone)
    // Wait for the row before creating the form state, so it starts from the real values.
    // No early return after a composable call (see AGENTS.md).
    if (shown != null && signedIn != null) EditForm(container, shown, signedIn, onDone)
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColumnScope.EditForm(
  container: AppContainer,
  original: TransactionEntity,
  profile: bd.family.expenses.data.settings.Profile,
  onDone: () -> Unit,
) {
  val householdId = profile.householdId
  val zone = ZoneId.of(profile?.timezone ?: "Asia/Dhaka")
  val reference = container.database.reference()
  val accounts by remember(householdId) { reference.observeAccounts(householdId) }.collectAsStateWithLifecycle(emptyList())
  val allCategories by remember(householdId) { reference.observeCategories(householdId) }.collectAsStateWithLifecycle(emptyList())
  val bangla = isBangla()
  val transfer = original.type == "transfer"
  // Archived choices stay available only if the entry already uses them.
  val categories =
    allCategories
      .filter { it.kind == original.type && (!it.isArchived || it.id == original.categoryId) }
      .sortedWith(compareBy({ it.nameEn.startsWith("Other", ignoreCase = true) }, { if (bangla) it.nameBn else it.nameEn }))
  val usable = accounts.filter { !it.isArchived || it.id == original.accountId || it.id == original.destinationAccountId }
  val scope = rememberCoroutineScope()
  val scroll = rememberScrollState()

  val today = LocalDate.now(zone)
  val originalDate = Instant.ofEpochMilli(original.occurredAtEpochMs).atZone(zone).toLocalDate()
  var amountText by rememberSaveable { mutableStateOf(Taka.toInputText(original.amountPoisha)) }
  var categoryId by rememberSaveable { mutableStateOf(original.categoryId) }
  var accountId by rememberSaveable { mutableStateOf<String?>(original.accountId) }
  var toId by rememberSaveable { mutableStateOf(original.destinationAccountId) }
  var dateText by rememberSaveable { mutableStateOf(originalDate.toString()) }
  var note by rememberSaveable { mutableStateOf(original.note ?: "") }
  var errors by remember { mutableStateOf(EntryErrors()) }
  var saving by remember { mutableStateOf(false) }
  var result by remember { mutableStateOf<Boolean?>(null) }
  val date = LocalDate.parse(dateText)

  Column(
    modifier = Modifier.weight(1f).verticalScroll(scroll).padding(horizontal = 20.dp, vertical = 8.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    AmountInput(amountText, errors.amount) { amountText = it }
    if (transfer) {
      Section(stringResource(R.string.from_account), errors.account)
      FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        usable.forEach { a ->
          Choice(a.name, a.id == accountId, onClick = { accountId = a.id; if (toId == a.id) toId = null })
        }
      }
      Section(stringResource(R.string.to_account), errors.destination)
      FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        usable.filter { it.id != accountId }.forEach { a -> Choice(a.name, a.id == toId, onClick = { toId = a.id }) }
      }
    } else {
      Section(stringResource(if (original.type == "income") R.string.income_category else R.string.category), errors.category)
      FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        categories.forEach { c ->
          Choice(categoryIcon(c.nameEn, c.kind) + "  " + (if (bangla) c.nameBn else c.nameEn), c.id == categoryId, onClick = { categoryId = c.id })
        }
      }
      Section(stringResource(if (original.type == "income") R.string.income_account else R.string.account), errors.account)
      FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        usable.forEach { a -> Choice(a.name, a.id == accountId, onClick = { accountId = a.id }) }
      }
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
    enabled = !saving,
    onSave = {
      val v = if (transfer) validateTransfer(amountText, accountId, toId) else validateEntry(amountText, categoryId, accountId)
      when (v) {
        is Validation.Invalid -> {
          errors = v.errors
          scope.launch { scroll.animateScrollTo(0) }
        }
        is Validation.Valid -> {
          errors = EntryErrors()
          saving = true
          scope.launch {
            result =
              container.changes.edit(
                original.clientId,
                EntryEdit(
                  amountPoisha = v.amountPoisha,
                  categoryId = if (transfer) null else categoryId,
                  accountId = accountId!!,
                  destinationAccountId = if (transfer) toId else null,
                  date = date,
                  note = note,
                ),
              )
            saving = false
          }
        }
      }
    },
  )

  result?.let { changed ->
    AlertDialog(
      onDismissRequest = onDone,
      confirmButton = { TextButton(onClick = onDone) { Text(stringResource(R.string.ok), style = MaterialTheme.typography.titleLarge) } },
      text = {
        Text(
          if (changed) "✓ " + stringResource(R.string.correction_saved) else stringResource(R.string.nothing_changed),
          style = MaterialTheme.typography.titleMedium,
        )
      },
    )
  }
}
