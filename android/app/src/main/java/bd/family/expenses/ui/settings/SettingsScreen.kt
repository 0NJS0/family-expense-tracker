package bd.family.expenses.ui.settings

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import bd.family.expenses.AppContainer
import bd.family.expenses.R
import bd.family.expenses.ui.common.BigButton
import bd.family.expenses.ui.common.ScreenBar
import bd.family.expenses.ui.common.formatDateTime
import bd.family.expenses.ui.common.isBangla
import bd.family.expenses.ui.theme.TouchTarget
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(container: AppContainer, onBack: () -> Unit) {
  val profile by container.settings.profile.collectAsStateWithLifecycle()
  val revision by container.settings.revision.collectAsStateWithLifecycle()
  val unsynced by remember { container.database.transactions().observeUnsyncedCount() }.collectAsStateWithLifecycle(0)
  val syncing by container.scheduler.isRunning.collectAsStateWithLifecycle(false)
  val lastSync = remember(revision) { container.settings.lastSyncAtMs }
  var confirmLogout by remember { mutableStateOf(false) }
  val scope = rememberCoroutineScope()

  Column(Modifier.fillMaxSize().safeDrawingPadding()) {
    ScreenBar(stringResource(R.string.settings), onBack = onBack)
    Column(
      Modifier.verticalScroll(rememberScrollState()).padding(20.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      Text(stringResource(R.string.language), style = MaterialTheme.typography.titleLarge)
      Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        LanguageButton("বাংলা", "bn", selected = isBangla(), modifier = Modifier.weight(1f))
        LanguageButton("English", "en", selected = !isBangla(), modifier = Modifier.weight(1f))
      }

      HorizontalDivider()
      Text(stringResource(R.string.sync_status), style = MaterialTheme.typography.titleLarge)
      Text(
        if (unsynced > 0) "⏳ " + stringResource(R.string.waiting_to_send, unsynced) else "✓ " + stringResource(R.string.all_sent),
        style = MaterialTheme.typography.bodyLarge,
      )
      Text(
        stringResource(
          R.string.last_sync,
          if (lastSync > 0) formatDateTime(lastSync, profile?.timezone ?: "Asia/Dhaka") else stringResource(R.string.never),
        ),
        style = MaterialTheme.typography.bodyLarge,
      )
      BigButton(
        stringResource(if (syncing) R.string.syncing else R.string.sync_now),
        onClick = { container.scheduler.syncNow() },
        enabled = !syncing,
      )

      HorizontalDivider()
      ReminderSettings(container)

      HouseholdSwitcher(container, unsynced)

      HorizontalDivider()
      profile?.let { Text(stringResource(R.string.signed_in_as, it.displayName, it.householdName), style = MaterialTheme.typography.bodyLarge) }
      if (unsynced > 0) {
        // Logging out would orphan unsent entries, so it is not offered until they are sent.
        Text(stringResource(R.string.logout_blocked, unsynced), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyLarge)
      }
      OutlinedButton(
        onClick = { confirmLogout = true },
        enabled = unsynced == 0,
        modifier = Modifier.fillMaxWidth().heightIn(min = TouchTarget),
      ) { Text(stringResource(R.string.logout), style = MaterialTheme.typography.titleMedium) }
    }
  }

  if (confirmLogout) {
    AlertDialog(
      onDismissRequest = { confirmLogout = false },
      title = { Text(stringResource(R.string.logout_confirm_title)) },
      text = { Text(stringResource(R.string.logout_confirm_text)) },
      confirmButton = {
        TextButton(onClick = {
          confirmLogout = false
          scope.launch {
            // Re-check: an entry may have been added since the button was shown.
            if (container.database.transactions().unsyncedCount() > 0) return@launch
            container.scheduler.cancelAll()
            container.reminders.cancel()
            container.session.logout()
            container.database.transactions().deleteAll()
            container.database.changes().deleteAll()
            container.database.receipts().deleteAll()
            container.database.reference().deleteAllAccounts()
            container.database.reference().deleteAllCategories()
            container.database.reference().deleteAllMembers()
            container.settings.clearProfile()
          }
        }) { Text(stringResource(R.string.logout)) }
      },
      dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text(stringResource(R.string.cancel)) } },
    )
  }
}

@Composable
private fun LanguageButton(label: String, tag: String, selected: Boolean, modifier: Modifier) {
  OutlinedButton(
    onClick = { AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag)) },
    modifier = modifier.heightIn(min = TouchTarget),
  ) {
    Text((if (selected) "✓ " else "") + label, style = MaterialTheme.typography.titleMedium)
  }
}

/**
 * Shown only to people in more than one family. Switching is blocked while
 * anything is unsent, so an entry can never wait under the wrong family.
 */
@Composable
private fun HouseholdSwitcher(container: AppContainer, unsynced: Int) {
  var memberships by remember { mutableStateOf<List<bd.family.expenses.data.remote.MembershipDto>>(emptyList()) }
  LaunchedEffect(Unit) {
    // Online only; without a connection the section simply stays hidden.
    memberships = runCatching { container.session.me().memberships }.getOrDefault(emptyList())
  }
  if (memberships.size >= 2) SwitcherList(container, memberships, unsynced)
}

@Composable
private fun SwitcherList(container: AppContainer, memberships: List<bd.family.expenses.data.remote.MembershipDto>, unsynced: Int) {
  val profile by container.settings.profile.collectAsStateWithLifecycle()
  val scope = rememberCoroutineScope()
  var problem by remember { mutableStateOf<Int?>(null) }
  HorizontalDivider()
  Text(stringResource(R.string.switch_family), style = MaterialTheme.typography.titleLarge)
  if (unsynced > 0) {
    Text(stringResource(R.string.switch_blocked, unsynced), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyLarge)
  }
  memberships.forEach { m ->
    val current = m.householdId == profile?.householdId
    OutlinedButton(
      onClick = {
        problem = null
        scope.launch {
          problem =
            try {
              if (container.session.switchHousehold(m.householdId) { container.database.transactions().unsyncedCount() }) {
                container.scheduler.syncNow()
                null
              } else {
                R.string.switch_blocked_short
              }
            } catch (e: kotlinx.coroutines.CancellationException) {
              throw e
            } catch (e: Exception) {
              R.string.due_needs_internet
            }
        }
      },
      enabled = !current && unsynced == 0,
      modifier = Modifier.fillMaxWidth().heightIn(min = TouchTarget),
    ) {
      Text((if (current) "✓ " else "") + m.householdName, style = MaterialTheme.typography.titleMedium)
    }
  }
  problem?.let { Text("⚠ " + stringResource(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyLarge) }
}
