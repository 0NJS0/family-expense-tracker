package bd.family.expenses.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import bd.family.expenses.AppContainer
import bd.family.expenses.R
import bd.family.expenses.data.remote.ApiException
import bd.family.expenses.data.remote.LoginRequiredException
import bd.family.expenses.data.remote.NetworkException
import bd.family.expenses.data.remote.OccurrenceDto
import bd.family.expenses.ui.common.formatDate
import bd.family.expenses.ui.common.money
import bd.family.expenses.ui.theme.TouchTarget
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Home: recurring entries that are due (rent, bills). A reminder only: "Paid"
 * records the expected amount after a confirmation; "Not this time" records
 * nothing. Both need the internet, and the card says so if it is missing.
 */
@Composable
fun DueCard(container: AppContainer, due: List<OccurrenceDto>, canResolve: Boolean, zone: String) {
  if (due.isNotEmpty()) DueCardContent(container, due, canResolve, zone)
}

@Composable
private fun DueCardContent(container: AppContainer, due: List<OccurrenceDto>, canResolve: Boolean, zone: String) {
  val scope = rememberCoroutineScope()
  var asking by remember { mutableStateOf<OccurrenceDto?>(null) }
  var busy by remember { mutableStateOf(false) }
  var problem by remember { mutableStateOf<String?>(null) }
  val offline = stringResource(R.string.due_needs_internet)

  fun resolve(item: OccurrenceDto, paid: Boolean) {
    busy = true
    problem = null
    scope.launch {
      try {
        container.session.resolveOccurrence(item.id, paid)
        // Gone from the list at once; the next sync brings the new entry and totals.
        container.settings.cachedDue = container.settings.cachedDue.orEmpty().filter { it.id != item.id }
        container.scheduler.syncNow()
      } catch (e: CancellationException) {
        throw e
      } catch (e: NetworkException) {
        problem = offline
      } catch (e: LoginRequiredException) {
        problem = offline
      } catch (e: ApiException) {
        problem = e.message
      } finally {
        busy = false
      }
    }
  }

  Card(
    modifier = Modifier.fillMaxWidth(),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
  ) {
    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      Text("🔔 " + stringResource(R.string.due_title), style = MaterialTheme.typography.titleLarge)
      due.forEach { item ->
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
          Text(item.ruleName + " — " + money(item.amountPoisha), style = MaterialTheme.typography.titleMedium)
          Text(
            stringResource(R.string.due_on, formatDate(LocalDate.parse(item.dueDate).atStartOfDay(ZoneId.of(zone)).toInstant().toEpochMilli(), zone)),
            style = MaterialTheme.typography.bodyLarge,
          )
          if (canResolve) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              Button(
                onClick = { asking = item },
                enabled = !busy,
                modifier = Modifier.weight(1f).heightIn(min = TouchTarget),
              ) { Text(stringResource(R.string.due_paid), style = MaterialTheme.typography.titleMedium) }
              OutlinedButton(
                onClick = { resolve(item, paid = false) },
                enabled = !busy,
                modifier = Modifier.weight(1f).heightIn(min = TouchTarget),
              ) { Text(stringResource(R.string.due_skip), style = MaterialTheme.typography.titleMedium) }
            }
          }
        }
      }
      problem?.let {
        Text(
          "⚠ $it",
          color = MaterialTheme.colorScheme.error,
          style = MaterialTheme.typography.bodyLarge,
          modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
      }
    }
  }

  asking?.let { item ->
    // A tap must not record money by accident: say exactly what will be written.
    AlertDialog(
      onDismissRequest = { asking = null },
      text = { Text(stringResource(R.string.due_confirm, item.ruleName, money(item.amountPoisha)), style = MaterialTheme.typography.titleMedium) },
      confirmButton = {
        TextButton(onClick = { asking = null; resolve(item, paid = true) }, modifier = Modifier.heightIn(min = TouchTarget)) {
          Text(stringResource(R.string.due_yes_paid), style = MaterialTheme.typography.titleMedium)
        }
      },
      dismissButton = {
        TextButton(onClick = { asking = null }, modifier = Modifier.heightIn(min = TouchTarget)) {
          Text(stringResource(R.string.cancel), style = MaterialTheme.typography.titleMedium)
        }
      },
    )
  }
}
