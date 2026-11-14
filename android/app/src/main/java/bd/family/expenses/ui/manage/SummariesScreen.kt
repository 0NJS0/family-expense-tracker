package bd.family.expenses.ui.manage

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import bd.family.expenses.AppContainer
import bd.family.expenses.R
import bd.family.expenses.ui.common.ScreenBar
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The checked AI summaries (admins, online). Writing them happens on the homelab, later. */
@Composable
fun SummariesScreen(container: AppContainer, onBack: () -> Unit) {
  val loader = rememberLoader(Unit) { container.admin.aiReports() }
  Column(Modifier.fillMaxSize().safeDrawingPadding()) {
    ScreenBar(stringResource(R.string.ai_summaries), onBack = onBack)
    Column(
      Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Text(stringResource(R.string.ai_summaries_explain), style = MaterialTheme.typography.bodyMedium)
      LoadView(loader) { reports ->
        val done = reports.filter { it.status == "done" && it.text != null }
        val waiting = reports.count { it.status == "queued" || it.status == "running" }
        if (waiting > 0) Text("⏳ " + stringResource(R.string.ai_summaries_waiting), style = MaterialTheme.typography.bodyLarge)
        if (done.isEmpty() && waiting == 0) Text(stringResource(R.string.ai_summaries_none), style = MaterialTheme.typography.bodyLarge)
        done.forEach { r ->
          Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
              val month = LocalDate.parse(r.periodStart)
              val locale = Locale.forLanguageTag(if (r.language == "bn") "bn-BD" else "en")
              Text(month.format(DateTimeFormatter.ofPattern("MMMM yyyy", locale)), style = MaterialTheme.typography.titleMedium)
              Text(r.text ?: "", style = MaterialTheme.typography.bodyLarge)
              Text("🤖 " + stringResource(R.string.ai_checked), style = MaterialTheme.typography.bodySmall)
            }
          }
        }
      }
    }
  }
}
