package bd.family.expenses.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import bd.family.expenses.AppContainer
import bd.family.expenses.R
import bd.family.expenses.data.remote.BudgetItemDto
import bd.family.expenses.data.remote.BudgetStatusDto
import bd.family.expenses.money.Taka
import bd.family.expenses.ui.common.ScreenBar
import bd.family.expenses.ui.common.isBangla
import bd.family.expenses.ui.common.money

/** "81.2%" from basis points, with integers only (rounded down, like the server). */
fun basisPointsText(bp: Long, bangla: Boolean): String {
  val text = "${bp / 100}.${(bp % 100) / 10}%"
  return if (bangla) Taka.toBanglaDigits(text) else text
}

/**
 * Home: the whole-family budget, plus any category that is close to or over its
 * budget. Server figures from the last sync; tap for every budget.
 */
@Composable
fun BudgetCard(status: BudgetStatusDto, onOpen: () -> Unit) {
  val overall = status.items.firstOrNull { it.categoryId == null }
  val attention = status.items.filter { it.categoryId != null && it.state != "ok" }
  if (overall != null || attention.isNotEmpty()) BudgetCardContent(overall, attention, onOpen)
}

@Composable
private fun BudgetCardContent(overall: BudgetItemDto?, attention: List<BudgetItemDto>, onOpen: () -> Unit) {
  Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      Text(stringResource(R.string.budget_this_month), style = MaterialTheme.typography.titleLarge)
      overall?.let { BudgetLine(it) }
      attention.forEach { BudgetLine(it) }
      Text(stringResource(R.string.budget_see_all), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
    }
  }
}

@Composable
fun BudgetLine(item: BudgetItemDto) {
  val bangla = isBangla()
  val name =
    if (item.categoryId == null) stringResource(R.string.budget_overall)
    else (if (bangla) item.nameBn else item.nameEn) ?: ""
  // Said in words and a symbol, never by colour alone.
  val state =
    when (item.state) {
      "over" -> "⚠ " + stringResource(R.string.budget_over)
      "warning" -> "● " + stringResource(R.string.budget_warning)
      else -> "✓ " + stringResource(R.string.budget_ok)
    }
  val spent = stringResource(R.string.budget_spent_of, money(item.spentPoisha), money(item.budgetPoisha))
  val left =
    if (item.remainingPoisha >= 0) stringResource(R.string.budget_left, money(item.remainingPoisha))
    else stringResource(R.string.budget_over_by, money(-item.remainingPoisha))
  val percent = basisPointsText(item.usedBasisPoints, bangla)
  Column(
    verticalArrangement = Arrangement.spacedBy(4.dp),
    modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = "$name, $state, $spent, $left" },
  ) {
    Text(name, style = MaterialTheme.typography.titleMedium)
    Text(state, style = MaterialTheme.typography.bodyLarge, color = if (item.state == "over") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
    LinearProgressIndicator(
      progress = { (item.usedBasisPoints.coerceIn(0, 10_000) / 10_000f) },
      color = if (item.state == "ok") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
      modifier = Modifier.fillMaxWidth().clearAndSetSemantics {},
    )
    Text("$spent ($percent)", style = MaterialTheme.typography.bodyLarge)
    Text(left, style = MaterialTheme.typography.bodyLarge)
  }
}

/** Every budget of this month. */
@Composable
fun BudgetsScreen(container: AppContainer, onBack: () -> Unit) {
  val revision by container.settings.revision.collectAsStateWithLifecycle()
  val status = remember(revision) { container.settings.cachedBudgets }
  Column(Modifier.fillMaxSize().safeDrawingPadding()) {
    ScreenBar(stringResource(R.string.budget_this_month), onBack = onBack)
    Column(
      Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      if (status == null || status.items.isEmpty()) {
        Text(stringResource(R.string.budget_none), style = MaterialTheme.typography.bodyLarge)
      } else {
        status.items.forEach { item ->
          Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(16.dp)) { BudgetLine(item) }
          }
        }
        Text(stringResource(R.string.budget_expenses_only), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
    }
  }
}
