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
import bd.family.expenses.ui.common.categoryIcon
import bd.family.expenses.ui.common.isBangla
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EntryScreen(container: AppContainer, type: String, onDone: () -> Unit) {
  val signedIn by container.settings.profile.collectAsStateWithLifecycle()
  // No early return after a composable call (see AGENTS.md): without a profile, show nothing.
  val profileNow = signedIn
  if (profileNow != null) EntryScreenContent(container, type, onDone, profileNow)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EntryScreenContent(container: AppContainer, type: String, onDone: () -> Unit, profile: bd.family.expenses.data.settings.Profile) {
  val householdId = profile.householdId
  val zone = ZoneId.of(profile?.timezone ?: "Asia/Dhaka")
  val reference = container.database.reference()
  val accounts by remember(householdId) { reference.observeAccounts(householdId) }.collectAsStateWithLifecycle(emptyList())
  val allCategories by remember(householdId) { reference.observeCategories(householdId) }.collectAsStateWithLifecycle(emptyList())
  val members by remember(householdId) { reference.observeMembers(householdId) }.collectAsStateWithLifecycle(emptyList())
  val bangla = isBangla()
  // Sorted by the name actually shown; the catch-all "Other …" categories go last.
  val categories =
    allCategories
      .filter { it.kind == type && !it.isArchived }
      .sortedWith(compareBy({ it.nameEn.startsWith("Other", ignoreCase = true) }, { if (bangla) it.nameBn else it.nameEn }))
  val usableAccounts = accounts.filter { !it.isArchived }
  val otherMembers = members.filter { it.isActive && it.userId != profile?.userId }
  val scope = rememberCoroutineScope()

  val today = LocalDate.now(zone)
  var amountText by rememberSaveable { mutableStateOf("") }
  var categoryId by rememberSaveable { mutableStateOf<String?>(null) }
  var accountId by rememberSaveable {
    mutableStateOf(container.settings.lastAccountId)
  }
  var dateText by rememberSaveable { mutableStateOf(today.toString()) }
  var note by rememberSaveable { mutableStateOf("") }
  var attributed by rememberSaveable { mutableStateOf<String?>(null) }
  var errors by remember { mutableStateOf(EntryErrors()) }
  val scroll = rememberScrollState()
  var saved by remember { mutableStateOf(false) }
  var saving by remember { mutableStateOf(false) }
  val date = LocalDate.parse(dateText)

  // Default to the only account, or the one used last time if it still exists.
  val effectiveAccount =
    accountId?.takeIf { id -> usableAccounts.any { it.id == id } } ?: usableAccounts.singleOrNull()?.id

  val title = stringResource(if (type == "income") R.string.income_title else R.string.expense_title)

  Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
    ScreenBar(title, onBack = onDone)
    Column(
      modifier = Modifier.weight(1f).verticalScroll(scroll).padding(horizontal = 20.dp, vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      if (usableAccounts.isEmpty() || categories.isEmpty()) {
        Text(stringResource(R.string.error_no_reference), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyLarge)
      }

      AmountInput(amountText, errors.amount) { amountText = it }

      Section(stringResource(if (type == "income") R.string.income_category else R.string.category), errors.category)
      FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        categories.forEach { c ->
          Choice(
            label = categoryIcon(c.nameEn, c.kind) + "  " + (if (bangla) c.nameBn else c.nameEn),
            selected = c.id == categoryId,
            onClick = { categoryId = c.id },
          )
        }
      }

      Section(stringResource(if (type == "income") R.string.income_account else R.string.account), errors.account)
      FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        usableAccounts.forEach { a -> Choice(a.name, a.id == effectiveAccount, onClick = { accountId = a.id }) }
      }

      DateInput(date, today, zone) { dateText = it.toString() }

      if (otherMembers.isNotEmpty()) {
        Section(stringResource(R.string.for_whom), null)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Choice(stringResource(R.string.myself), attributed == null, onClick = { attributed = null })
          otherMembers.forEach { m -> Choice(m.displayName, attributed == m.userId, onClick = { attributed = m.userId }) }
        }
      }

      OutlinedTextField(
        value = note,
        onValueChange = { note = it.take(500) },
        label = { Text(stringResource(R.string.note)) },
        modifier = Modifier.fillMaxWidth(),
      )
    }
    // Always visible: the person never has to scroll to find Save.
    SaveBar(
      hasErrors = !errors.isEmpty,
      enabled = !saving && usableAccounts.isNotEmpty() && categories.isNotEmpty(),
      onSave = {
        when (val v = validateEntry(amountText, categoryId, effectiveAccount)) {
          is Validation.Invalid -> {
            errors = v.errors
            // The problems are shown at the top of the form: bring them into view.
            scope.launch { scroll.animateScrollTo(0) }
          }
          is Validation.Valid -> {
            errors = EntryErrors()
            saving = true
            scope.launch {
              container.entries.save(NewEntry(type, v.amountPoisha, categoryId!!, effectiveAccount!!, date, note, attributed))
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
