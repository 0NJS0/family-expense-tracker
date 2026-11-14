package bd.family.expenses.ui.manage

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import bd.family.expenses.AppContainer
import bd.family.expenses.R
import bd.family.expenses.data.remote.AccountFullDto
import bd.family.expenses.data.remote.CategoryDto
import bd.family.expenses.data.remote.MemberFullDto
import bd.family.expenses.money.Taka
import bd.family.expenses.ui.common.ScreenBar
import bd.family.expenses.ui.common.categoryIcon
import bd.family.expenses.ui.common.money
import java.time.LocalDate
import java.time.ZoneId
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.launch

/** Runs an admin change; refreshes the list and the phone's cached reference data on success. */
private class Mutator(
  private val scope: kotlinx.coroutines.CoroutineScope,
  private val onDone: () -> Unit,
) {
  var busy by mutableStateOf(false)
  var error by mutableStateOf<Throwable?>(null)

  fun run(action: suspend () -> Unit, after: () -> Unit = {}) {
    busy = true
    error = null
    scope.launch {
      try {
        action()
        after()
        onDone()
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        error = e
      } finally {
        busy = false
      }
    }
  }
}

@Composable
private fun rememberMutator(container: AppContainer, reload: () -> Unit): Mutator {
  val scope = rememberCoroutineScope()
  return remember(reload) {
    Mutator(scope) {
      reload()
      container.scheduler.syncNow()
    }
  }
}

@Composable
private fun ManageScaffold(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
  Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
    ScreenBar(title, onBack = onBack)
    Column(
      Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(14.dp),
    ) { content() }
  }
}

// --- Accounts --------------------------------------------------------------------

private val KINDS = listOf("cash" to R.string.kind_cash, "bank" to R.string.kind_bank, "mobile_wallet" to R.string.kind_mobile_wallet, "other" to R.string.kind_other)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AccountsAdminScreen(container: AppContainer, onBack: () -> Unit) {
  val profile by container.settings.profile.collectAsStateWithLifecycle()
  val zone = ZoneId.of(profile?.timezone ?: "Asia/Dhaka")
  val list = rememberLoader(Unit) { container.admin.accounts() to container.admin.balances() }
  val mutator = rememberMutator(container, list.reload)

  ManageScaffold(stringResource(R.string.accounts_title), onBack) {
    mutator.error?.let { ErrorBox(it) }
    LoadView(list) { (accounts, balances) ->
      val balanceById = balances.accounts.associate { it.accountId to it.balancePoisha }
      SectionCard(stringResource(R.string.accounts_title)) {
        accounts.forEach { a ->
          AccountRow(a, balanceById[a.id], mutator, container)
          HorizontalDivider()
        }
      }
    }

    // Add account
    var name by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf("cash") }
    var provider by remember { mutableStateOf("") }
    var opening by remember { mutableStateOf("") }
    var openingError by remember { mutableStateOf(false) }
    SectionCard(stringResource(R.string.add_account)) {
      OutlinedTextField(name, { name = it.take(100) }, label = { Text(stringResource(R.string.account_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
      Text(stringResource(R.string.kind), style = MaterialTheme.typography.titleMedium)
      FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        KINDS.forEach { (value, label) ->
          FilterChip(selected = kind == value, onClick = { kind = value }, label = { Text(stringResource(label)) }, leadingIcon = if (kind == value) ({ Text("✓") }) else null)
        }
      }
      OutlinedTextField(provider, { provider = it.take(100) }, label = { Text(stringResource(R.string.provider)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
      OutlinedTextField(
        opening,
        { opening = it.take(20); openingError = false },
        label = { Text(stringResource(R.string.opening_balance)) },
        isError = openingError,
        supportingText = if (openingError) ({ Text(stringResource(R.string.opening_invalid)) }) else null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
      )
      SmallButton(stringResource(R.string.create), primary = true, enabled = name.isNotBlank() && !mutator.busy, onClick = {
        val openingPoisha = if (opening.isBlank()) null else Taka.parseSigned(opening)
        if (opening.isNotBlank() && openingPoisha == null) {
          openingError = true
          return@SmallButton
        }
        mutator.run(
          { container.admin.createAccount(name.trim(), kind, provider.trim().ifEmpty { null }, openingPoisha, openingPoisha?.let { LocalDate.now(zone).toString() }) },
          after = { name = ""; provider = ""; opening = "" },
        )
      })
    }
  }
}

@Composable
private fun AccountRow(account: AccountFullDto, balance: Long?, mutator: Mutator, container: AppContainer) {
  var renaming by remember(account.id) { mutableStateOf(false) }
  var newName by remember(account.id, account.name) { mutableStateOf(account.name) }
  Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
      Column(Modifier.weight(1f)) {
        Text(
          (if (account.isPrivate) "🔒 " else "") + account.name +
            (if (account.isPrivate) " (" + stringResource(R.string.private_account) + ")" else "") +
            (if (account.isArchived) " (" + stringResource(R.string.archived) + ")" else ""),
          style = MaterialTheme.typography.titleMedium,
        )
        account.institution?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
      }
      balance?.let { Text(money(it), style = MaterialTheme.typography.titleMedium) }
    }
    if (renaming) {
      OutlinedTextField(newName, { newName = it.take(100) }, label = { Text(stringResource(R.string.account_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      if (renaming) {
        SmallButton(stringResource(R.string.save), primary = true, enabled = newName.isNotBlank() && !mutator.busy, onClick = {
          mutator.run({ container.admin.updateAccount(account.id, name = newName.trim()) }, after = { renaming = false })
        })
        SmallButton(stringResource(R.string.cancel), onClick = { renaming = false })
      } else {
        SmallButton(stringResource(R.string.rename), onClick = { renaming = true })
        SmallButton(stringResource(if (account.isArchived) R.string.restore else R.string.archive), enabled = !mutator.busy, onClick = {
          mutator.run({ container.admin.updateAccount(account.id, archived = !account.isArchived) })
        })
      }
    }
  }
}

// --- Categories ------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CategoriesAdminScreen(container: AppContainer, onBack: () -> Unit) {
  val list = rememberLoader(Unit) { container.admin.categories() }
  val mutator = rememberMutator(container, list.reload)

  ManageScaffold(stringResource(R.string.categories_title), onBack) {
    mutator.error?.let { ErrorBox(it) }
    LoadView(list) { categories ->
      listOf("expense" to R.string.expense_categories, "income" to R.string.income_categories).forEach { (kind, title) ->
        SectionCard(stringResource(title)) {
          categories.filter { it.kind == kind }.forEach { c ->
            CategoryRow(c, mutator, container)
            HorizontalDivider()
          }
        }
      }
    }
    var kind by remember { mutableStateOf("expense") }
    var bn by remember { mutableStateOf("") }
    var en by remember { mutableStateOf("") }
    SectionCard(stringResource(R.string.add_category)) {
      FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("expense" to R.string.expense, "income" to R.string.income).forEach { (value, label) ->
          FilterChip(selected = kind == value, onClick = { kind = value }, label = { Text(stringResource(label)) }, leadingIcon = if (kind == value) ({ Text("✓") }) else null)
        }
      }
      OutlinedTextField(bn, { bn = it.take(100) }, label = { Text(stringResource(R.string.name_bn)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
      OutlinedTextField(en, { en = it.take(100) }, label = { Text(stringResource(R.string.name_en)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
      SmallButton(stringResource(R.string.create), primary = true, enabled = bn.isNotBlank() && en.isNotBlank() && !mutator.busy, onClick = {
        mutator.run({ container.admin.createCategory(kind, bn.trim(), en.trim()) }, after = { bn = ""; en = "" })
      })
    }
  }
}

@Composable
private fun CategoryRow(category: CategoryDto, mutator: Mutator, container: AppContainer) {
  var renaming by remember(category.id) { mutableStateOf(false) }
  var bn by remember(category.id, category.nameBn) { mutableStateOf(category.nameBn) }
  var en by remember(category.id, category.nameEn) { mutableStateOf(category.nameEn) }
  Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
    Text(
      categoryIcon(category.nameEn, category.kind) + "  " + category.nameBn + " · " + category.nameEn +
        if (category.isArchived) " (" + stringResource(R.string.archived) + ")" else "",
      style = MaterialTheme.typography.titleMedium,
    )
    if (renaming) {
      OutlinedTextField(bn, { bn = it.take(100) }, label = { Text(stringResource(R.string.name_bn)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
      OutlinedTextField(en, { en = it.take(100) }, label = { Text(stringResource(R.string.name_en)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      if (renaming) {
        SmallButton(stringResource(R.string.save), primary = true, enabled = bn.isNotBlank() && en.isNotBlank() && !mutator.busy, onClick = {
          mutator.run({ container.admin.updateCategory(category.id, nameBn = bn.trim(), nameEn = en.trim()) }, after = { renaming = false })
        })
        SmallButton(stringResource(R.string.cancel), onClick = { renaming = false })
      } else {
        SmallButton(stringResource(R.string.rename), onClick = { renaming = true })
        SmallButton(stringResource(if (category.isArchived) R.string.restore else R.string.archive), enabled = !mutator.busy, onClick = {
          mutator.run({ container.admin.updateCategory(category.id, archived = !category.isArchived) })
        })
      }
    }
  }
}

// --- Members ---------------------------------------------------------------------

private val ROLES = listOf("admin" to R.string.role_admin, "member" to R.string.role_member, "viewer" to R.string.role_viewer)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MembersAdminScreen(container: AppContainer, onBack: () -> Unit) {
  val profile by container.settings.profile.collectAsStateWithLifecycle()
  val list = rememberLoader(Unit) { container.admin.members() }
  val mutator = rememberMutator(container, list.reload)
  val context = LocalContext.current

  ManageScaffold(stringResource(R.string.members_title), onBack) {
    mutator.error?.let { ErrorBox(it) }
    LoadView(list) { members ->
      SectionCard(stringResource(R.string.members_title)) {
        members.forEach { m ->
          MemberRow(m, isMe = m.userId == profile?.userId, mutator, container)
          HorizontalDivider()
        }
      }
    }

    var name by remember { mutableStateOf("") }
    var role by remember { mutableStateOf("member") }
    var link by remember { mutableStateOf<String?>(null) }
    val message = link?.let { stringResource(R.string.invite_message, it) }
    SectionCard(stringResource(R.string.invite)) {
      OutlinedTextField(name, { name = it.take(100) }, label = { Text(stringResource(R.string.invite_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
      FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ROLES.forEach { (value, label) ->
          FilterChip(selected = role == value, onClick = { role = value }, label = { Text(stringResource(label)) }, leadingIcon = if (role == value) ({ Text("✓") }) else null)
        }
      }
      SmallButton(stringResource(R.string.create_invite), primary = true, enabled = name.isNotBlank() && !mutator.busy, onClick = {
        mutator.run({
          val invitation = container.admin.invite(name.trim(), role)
          // The invitation page is served with the dashboard on the household server.
          link = (profile?.serverUrl ?: "") + "/accept?token=" + java.net.URLEncoder.encode(invitation.token, "UTF-8")
          name = ""
        })
      })
      if (link != null && message != null) {
        Text("✓ " + stringResource(R.string.invite_created), style = MaterialTheme.typography.bodyLarge)
        Text(link!!, style = MaterialTheme.typography.bodyMedium)
        SmallButton(stringResource(R.string.share_invite), primary = true, onClick = {
          val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, message)
          context.startActivity(Intent.createChooser(send, null))
        })
      }
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MemberRow(member: MemberFullDto, isMe: Boolean, mutator: Mutator, container: AppContainer) {
  Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
    Text(
      member.displayName + "  @" + member.username + if (!member.isActive) " (" + stringResource(R.string.removed) + ")" else "",
      style = MaterialTheme.typography.titleMedium,
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
      ROLES.forEach { (value, label) ->
        FilterChip(
          selected = member.role == value,
          enabled = !mutator.busy,
          onClick = { if (member.role != value) mutator.run({ container.admin.updateMember(member.userId, role = value) }) },
          label = { Text(stringResource(label)) },
          leadingIcon = if (member.role == value) ({ Text("✓") }) else null,
        )
      }
    }
    if (!isMe) {
      SmallButton(stringResource(if (member.isActive) R.string.remove_member else R.string.restore_member), enabled = !mutator.busy, onClick = {
        mutator.run({ container.admin.updateMember(member.userId, active = !member.isActive) })
      })
    }
  }
}
