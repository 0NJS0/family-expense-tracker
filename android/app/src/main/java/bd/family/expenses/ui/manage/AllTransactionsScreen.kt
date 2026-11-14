package bd.family.expenses.ui.manage

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import bd.family.expenses.AppContainer
import bd.family.expenses.R
import bd.family.expenses.data.local.AccountEntity
import bd.family.expenses.data.local.CategoryEntity
import bd.family.expenses.data.remote.AdminApi
import bd.family.expenses.data.remote.AdminTransactionDto
import bd.family.expenses.data.remote.ApiException
import bd.family.expenses.money.Taka
import bd.family.expenses.ui.common.ScreenBar
import bd.family.expenses.ui.common.categoryIcon
import bd.family.expenses.ui.common.formatDate
import bd.family.expenses.ui.common.isBangla
import bd.family.expenses.ui.common.money
import bd.family.expenses.ui.entry.sanitiseAmountInput
import bd.family.expenses.ui.theme.TouchTarget
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AllTransactionsScreen(container: AppContainer, onBack: () -> Unit) {
  val signedIn by container.settings.profile.collectAsStateWithLifecycle()
  // No early return after a composable call (see AGENTS.md): without a profile, show nothing.
  val profileNow = signedIn
  if (profileNow != null) AllTransactionsScreenContent(container, onBack, profileNow)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AllTransactionsScreenContent(container: AppContainer, onBack: () -> Unit, profile: bd.family.expenses.data.settings.Profile) {
  val householdId = profile.householdId
  val zone = ZoneId.of(profile?.timezone ?: "Asia/Dhaka")
  val reference = container.database.reference()
  val categories by remember(householdId) { reference.observeCategories(householdId) }.collectAsStateWithLifecycle(emptyList())
  val accounts by remember(householdId) { reference.observeAccounts(householdId) }.collectAsStateWithLifecycle(emptyList())
  val members by remember(householdId) { reference.observeMembers(householdId) }.collectAsStateWithLifecycle(emptyList())
  var period by rememberSaveable { mutableStateOf(Period.THIS_MONTH) }
  var type by rememberSaveable { mutableStateOf<String?>(null) }
  val items = remember { mutableStateListOf<AdminTransactionDto>() }
  var total by remember { mutableIntStateOf(0) }
  var loading by remember { mutableStateOf(true) }
  var error by remember { mutableStateOf<Throwable?>(null) }
  var tick by remember { mutableIntStateOf(0) }
  var open by remember { mutableStateOf<AdminTransactionDto?>(null) }
  val scope = rememberCoroutineScope()
  val (from, to) = periodRange(period, LocalDate.now(zone))

  suspend fun loadPage(offset: Int) {
    loading = true
    error = null
    try {
      val page = container.admin.transactions(from.toString(), to.toString(), type, offset)
      if (offset == 0) items.clear()
      items.addAll(page.items)
      total = page.total
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      error = e
    } finally {
      loading = false
    }
  }

  LaunchedEffect(period, type, tick) { loadPage(0) }

  Column(Modifier.fillMaxSize().safeDrawingPadding()) {
    ScreenBar(stringResource(R.string.all_transactions), onBack = onBack)
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      PeriodChips(period) { period = it }
      FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(null to R.string.type_all, "expense" to R.string.expense, "income" to R.string.income, "transfer" to R.string.transfer)
          .forEach { (value, label) ->
            FilterChip(
              selected = type == value,
              onClick = { type = value },
              label = { Text(stringResource(label), style = MaterialTheme.typography.titleMedium) },
              leadingIcon = if (type == value) ({ Text("✓") }) else null,
            )
          }
      }
      if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
      error?.let { ErrorBox(it) { tick++ } }
    }
    LazyColumn(
      modifier = Modifier.fillMaxSize(),
      verticalArrangement = Arrangement.spacedBy(10.dp),
      contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
    ) {
      if (!loading && error == null && items.isEmpty()) {
        item { Text(stringResource(R.string.nothing_here), style = MaterialTheme.typography.bodyLarge) }
      }
      items(items, key = { it.id }) { tx ->
        TxRow(tx, categories, accounts, members.associate { it.userId to it.displayName }, zone.id) { open = tx }
      }
      if (items.size < total) {
        item {
          OutlinedButton(
            onClick = { scope.launch { loadPage(items.size) } },
            enabled = !loading,
            modifier = Modifier.fillMaxWidth().heightIn(min = TouchTarget),
          ) { Text(stringResource(R.string.show_more), style = MaterialTheme.typography.titleMedium) }
        }
      }
    }
  }

  open?.let { tx ->
    TransactionDialog(
      tx = tx,
      api = container.admin,
      categories = categories,
      accounts = accounts,
      onDismiss = { open = null },
      onChanged = { updated ->
        val index = items.indexOfFirst { it.id == updated.id }
        if (index >= 0) items[index] = updated
        open = updated
        // Bring the phone's own cached history and totals up to date.
        container.scheduler.syncNow()
      },
    )
  }
}

@Composable
private fun TxRow(
  tx: AdminTransactionDto,
  categories: List<CategoryEntity>,
  accounts: List<AccountEntity>,
  names: Map<String, String>,
  zone: String,
  onClick: () -> Unit,
) {
  val bangla = isBangla()
  val category = categories.firstOrNull { it.id == tx.categoryId }
  val accountName = { id: String? -> accounts.firstOrNull { it.id == id }?.name ?: "" }
  val title =
    if (tx.type == "transfer") "↔  " + accountName(tx.accountId) + " → " + accountName(tx.destinationAccountId)
    else (category?.let { categoryIcon(it.nameEn, it.kind) + "  " + (if (bangla) it.nameBn else it.nameEn) } ?: "")
  val amount = money(if (tx.type == "expense") -tx.amountPoisha else tx.amountPoisha)
  val date = formatDate(OffsetDateTime.parse(tx.occurredAt).toInstant().toEpochMilli(), zone)
  val voided = tx.voidedAt != null
  Card(
    modifier =
      Modifier.fillMaxWidth().clickable(onClick = onClick).semantics(mergeDescendants = true) {
        contentDescription = "$date, $title, $amount" + if (voided) ", voided" else ""
      },
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    border = if (voided) BorderStroke(1.dp, MaterialTheme.colorScheme.outline) else null,
  ) {
    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
      Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        Text(amount, style = MaterialTheme.typography.titleMedium)
      }
      Text(
        listOfNotNull(date, names[tx.attributedUserId], accountName(tx.accountId).takeIf { tx.type != "transfer" })
          .joinToString(" · "),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      tx.note?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
      if (voided) Text("⊘ " + stringResource(R.string.voided) + (tx.voidReason?.let { " — $it" } ?: ""), style = MaterialTheme.typography.labelMedium)
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TransactionDialog(
  tx: AdminTransactionDto,
  api: AdminApi,
  categories: List<CategoryEntity>,
  accounts: List<AccountEntity>,
  onDismiss: () -> Unit,
  onChanged: (AdminTransactionDto) -> Unit,
) {
  val bangla = isBangla()
  val scope = rememberCoroutineScope()
  var mode by remember(tx.id, tx.version) { mutableStateOf("view") }
  // Fields start from the version being shown; a reload (new version) resets them.
  var amount by remember(tx.id, tx.version) { mutableStateOf(Taka.toInputText(tx.amountPoisha)) }
  var categoryId by remember(tx.id, tx.version) { mutableStateOf(tx.categoryId) }
  var accountId by remember(tx.id, tx.version) { mutableStateOf(tx.accountId) }
  var note by remember(tx.id, tx.version) { mutableStateOf(tx.note ?: "") }
  var reason by remember(tx.id, tx.version) { mutableStateOf("") }
  var busy by remember { mutableStateOf(false) }
  var problem by remember { mutableStateOf<Throwable?>(null) }
  var amountError by remember { mutableStateOf(false) }
  var notice by remember { mutableStateOf<String?>(null) }
  val conflictText = stringResource(R.string.changed_elsewhere)
  val savedText = stringResource(R.string.saved_short)
  val editable = tx.voidedAt == null

  fun run(action: suspend () -> AdminTransactionDto) {
    busy = true
    problem = null
    scope.launch {
      try {
        val updated = action()
        notice = savedText
        mode = "view"
        onChanged(updated)
      } catch (e: CancellationException) {
        throw e
      } catch (e: ApiException) {
        if (e.status == 409) {
          // Never overwrite: show the latest version and let the admin decide again.
          runCatching { api.transaction(tx.id) }.getOrNull()?.let(onChanged)
          notice = conflictText
          mode = "view"
        } else {
          problem = e
        }
      } catch (e: Exception) {
        problem = e
      } finally {
        busy = false
      }
    }
  }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(money(tx.amountPoisha), style = MaterialTheme.typography.headlineSmall) },
    text = {
      Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        notice?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
        problem?.let { ErrorBox(it) }
        when (mode) {
          "edit" -> {
            OutlinedTextField(
              value = amount,
              onValueChange = { amount = sanitiseAmountInput(it); amountError = false },
              label = { Text(stringResource(R.string.amount)) },
              isError = amountError,
              supportingText = if (amountError) ({ Text(stringResource(R.string.error_amount_format)) }) else null,
              keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
              singleLine = true,
            )
            if (tx.type != "transfer") {
              FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                categories.filter { it.kind == tx.type && (!it.isArchived || it.id == tx.categoryId) }.forEach { c ->
                  FilterChip(
                    selected = categoryId == c.id,
                    onClick = { categoryId = c.id },
                    label = { Text(categoryIcon(c.nameEn, c.kind) + " " + (if (bangla) c.nameBn else c.nameEn)) },
                  )
                }
              }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
              accounts.filter { !it.isArchived || it.id == tx.accountId }.forEach { a ->
                FilterChip(selected = accountId == a.id, onClick = { accountId = a.id }, label = { Text(a.name) })
              }
            }
            OutlinedTextField(value = note, onValueChange = { note = it.take(500) }, label = { Text(stringResource(R.string.note)) })
          }
          "void" -> {
            Text(stringResource(R.string.void_help), style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(value = reason, onValueChange = { reason = it.take(500) }, label = { Text(stringResource(R.string.void_reason)) })
          }
          else -> {
            tx.note?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
            if (!editable) Text("⊘ " + stringResource(R.string.voided) + (tx.voidReason?.let { " — $it" } ?: ""))
          }
        }
      }
    },
    confirmButton = {
      when (mode) {
        "edit" ->
          TextButton(enabled = !busy, onClick = {
            val parsed = Taka.parse(amount)
            if (parsed !is Taka.Parsed.Ok) {
              amountError = true
              return@TextButton
            }
            // Send only what changed, with the version that was shown.
            run {
              api.updateTransaction(
                tx.id,
                tx.version,
                amountPoisha = parsed.poisha.takeIf { it != tx.amountPoisha },
                categoryId = categoryId.takeIf { it != tx.categoryId },
                accountId = accountId.takeIf { it != tx.accountId },
                note = note.trim().takeIf { it.isNotEmpty() && it != (tx.note ?: "") },
                clearNote = note.isBlank() && !tx.note.isNullOrBlank(),
              )
            }
          }) { Text(stringResource(R.string.save)) }
        "void" ->
          TextButton(enabled = !busy && reason.isNotBlank(), onClick = { run { api.voidTransaction(tx.id, tx.version, reason.trim()) } }) {
            Text(stringResource(R.string.void_confirm))
          }
        else ->
          if (editable) TextButton(onClick = { mode = "edit"; notice = null }) { Text(stringResource(R.string.edit)) }
      }
    },
    dismissButton = {
      Row {
        if (mode == "view" && editable) {
          TextButton(onClick = { mode = "void"; notice = null }) { Text(stringResource(R.string.void_action), color = MaterialTheme.colorScheme.error) }
        }
        TextButton(onClick = { if (mode == "view") onDismiss() else mode = "view" }) {
          Text(stringResource(if (mode == "view") R.string.ok else R.string.cancel))
        }
      }
    },
  )
}
