package bd.family.expenses.ui.settings

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import android.content.pm.PackageManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import bd.family.expenses.AppContainer
import bd.family.expenses.R
import bd.family.expenses.data.remote.NotificationPrefsDto
import bd.family.expenses.money.Taka
import bd.family.expenses.ui.common.isBangla
import bd.family.expenses.ui.theme.TouchTarget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private val TIMES = listOf("18:00", "19:00", "20:00", "21:00", "22:00")

/** Reminder choices. Saved on the server (they follow the person); needs the internet to change. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReminderSettings(container: AppContainer) {
  val context = LocalContext.current
  val profile by container.settings.profile.collectAsStateWithLifecycle()
  val revision by container.settings.revision.collectAsStateWithLifecycle()
  val prefs = remember(revision) { container.settings.notificationPrefs }
  val scope = rememberCoroutineScope()
  var problem by remember { mutableStateOf<Int?>(null) }
  var saving by remember { mutableStateOf(false) }
  val permission =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { /* the result shows below */ }
  val bangla = isBangla()

  fun save(next: NotificationPrefsDto) {
    val granted =
      Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    if (!granted && (next.dailyReminder || next.missingEntryReminder || next.recurringDue || next.budgetAlerts)) {
      permission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    saving = true
    problem = null
    scope.launch {
      try {
        val saved = container.session.saveNotificationPrefs(next)
        container.settings.notificationPrefs = saved
        profile?.let { container.reminders.schedule(saved, it.timezone) }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        problem = R.string.due_needs_internet
      } finally {
        saving = false
      }
    }
  }

  Text(stringResource(R.string.reminders_title), style = MaterialTheme.typography.titleLarge)
  ToggleRow(stringResource(R.string.reminder_missing), prefs.missingEntryReminder, !saving) { save(prefs.copy(missingEntryReminder = it)) }
  ToggleRow(stringResource(R.string.reminder_daily), prefs.dailyReminder, !saving) { save(prefs.copy(dailyReminder = it)) }
  if (prefs.dailyReminder || prefs.missingEntryReminder) {
    Text(stringResource(R.string.reminder_time), style = MaterialTheme.typography.titleMedium)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      TIMES.forEach { t ->
        val selected = prefs.reminderTime.startsWith(t)
        FilterChip(
          selected = selected,
          enabled = !saving,
          onClick = { save(prefs.copy(reminderTime = "$t:00")) },
          label = { Text((if (selected) "✓ " else "") + if (bangla) Taka.toBanglaDigits(t) else t, style = MaterialTheme.typography.titleMedium) },
          modifier = Modifier.heightIn(min = TouchTarget),
        )
      }
    }
  }
  ToggleRow(stringResource(R.string.reminder_due), prefs.recurringDue, !saving) { save(prefs.copy(recurringDue = it)) }
  if (profile?.isAdmin == true) {
    ToggleRow(stringResource(R.string.reminder_budget), prefs.budgetAlerts, !saving) { save(prefs.copy(budgetAlerts = it)) }
  }
  if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
    Text(stringResource(R.string.notifications_blocked), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyLarge)
  }
  problem?.let { Text("⚠ " + stringResource(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyLarge) }
}

/** A whole-row switch: the label is part of the touch target and read with the state. */
@Composable
private fun ToggleRow(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
  Row(
    modifier =
      Modifier.fillMaxWidth().heightIn(min = TouchTarget).toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
    Switch(checked = checked, onCheckedChange = null, enabled = enabled)
  }
}
