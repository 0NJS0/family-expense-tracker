package bd.family.expenses.ui.manage

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import bd.family.expenses.AppContainer
import bd.family.expenses.R
import bd.family.expenses.ui.common.LabeledRow
import bd.family.expenses.ui.common.ScreenBar
import bd.family.expenses.ui.common.categoryIcon
import bd.family.expenses.ui.common.isBangla
import bd.family.expenses.ui.common.money
import bd.family.expenses.ui.theme.TouchTarget
import java.time.LocalDate
import java.time.ZoneId

enum class Period { THIS_MONTH, LAST_MONTH, THIS_YEAR }

/** Inclusive local date range for a period, in the household timezone. */
fun periodRange(period: Period, today: LocalDate): Pair<LocalDate, LocalDate> =
  when (period) {
    Period.THIS_MONTH -> today.withDayOfMonth(1) to today
    Period.LAST_MONTH -> {
      val last = today.withDayOfMonth(1).minusDays(1)
      last.withDayOfMonth(1) to last
    }
    Period.THIS_YEAR -> today.withDayOfYear(1) to today
  }

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PeriodChips(period: Period, onChange: (Period) -> Unit) {
  FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    listOf(Period.THIS_MONTH to R.string.this_month, Period.LAST_MONTH to R.string.last_month, Period.THIS_YEAR to R.string.this_year)
      .forEach { (p, label) ->
        FilterChip(
          selected = period == p,
          onClick = { onChange(p) },
          label = { Text(stringResource(label), style = MaterialTheme.typography.titleMedium) },
          modifier = Modifier.padding(vertical = 2.dp),
          leadingIcon = if (period == p) ({ Text("✓") }) else null,
        )
      }
  }
}

@Composable
fun ReportsScreen(container: AppContainer, onBack: () -> Unit) {
  val profile by container.settings.profile.collectAsStateWithLifecycle()
  val zone = ZoneId.of(profile?.timezone ?: "Asia/Dhaka")
  var period by rememberSaveable { mutableStateOf(Period.THIS_MONTH) }
  val (from, to) = periodRange(period, LocalDate.now(zone))
  val summary = rememberLoader(period) { container.admin.summary(from.toString(), to.toString()) }
  val balances = rememberLoader(Unit) { container.admin.balances() }
  val top =
    rememberLoader(period) { container.admin.transactions(from.toString(), to.toString(), "expense", 0, limit = 5, largestFirst = true) }
  val bangla = isBangla()
  val periodLabel =
    stringResource(
      when (period) {
        Period.THIS_MONTH -> R.string.this_month
        Period.LAST_MONTH -> R.string.last_month
        Period.THIS_YEAR -> R.string.this_year
      }
    )

  Column(Modifier.fillMaxSize().safeDrawingPadding()) {
    ScreenBar(stringResource(R.string.reports), onBack = onBack)
    Column(
      Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
      PeriodChips(period) { period = it }

      LoadView(summary) { s ->
        SectionCard(periodLabel) {
          LabeledRow(stringResource(R.string.spent), money(s.expensePoisha))
          LabeledRow(stringResource(R.string.received), money(s.incomePoisha))
          LabeledRow(stringResource(R.string.net), money(s.netPoisha))
          LabeledRow(stringResource(R.string.transfers_note), money(s.transferPoisha))
          if (s.comparison.transactionCount > 0) {
            Text(
              stringResource(R.string.change_vs_previous, money(s.comparison.expenseChangePoisha, showSign = true)) +
                " (" + stringResource(R.string.spent) + ")",
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }

        SectionCard(stringResource(R.string.spending_by_category)) {
          if (s.expenseByCategory.isEmpty()) Text(stringResource(R.string.nothing_here))
          s.expenseByCategory.forEach { c ->
            ShareBar(
              label = categoryIcon(c.nameEn, c.kind) + "  " + (if (bangla) c.nameBn else c.nameEn),
              amount = money(c.totalPoisha),
              fraction = if (s.expensePoisha > 0) c.totalPoisha.toFloat() / s.expensePoisha else 0f,
            )
          }
        }

        SectionCard(stringResource(R.string.who_spent)) {
          val spenders = s.byMember.filter { it.expensePoisha > 0 }
          if (spenders.isEmpty()) Text(stringResource(R.string.nothing_here))
          spenders.forEach { m ->
            ShareBar(m.displayName, money(m.expensePoisha), if (s.expensePoisha > 0) m.expensePoisha.toFloat() / s.expensePoisha else 0f)
          }
        }
      }

      SectionCard(stringResource(R.string.biggest_expenses)) {
        LoadView(top) { page ->
          if (page.items.isEmpty()) Text(stringResource(R.string.nothing_here))
          page.items.forEach { tx -> LabeledRow(tx.note?.takeIf { it.isNotBlank() } ?: tx.occurredAt.take(10), money(tx.amountPoisha)) }
        }
      }

      SectionCard(stringResource(R.string.balances_title)) {
        LoadView(balances) { b ->
          b.accounts.filter { !it.isArchived }.forEach { LabeledRow(it.name, money(it.balancePoisha)) }
          LabeledRow(stringResource(R.string.total_balance), money(b.totalPoisha))
        }
      }
      androidx.compose.foundation.layout.Spacer(Modifier.padding(bottom = TouchTarget / 2))
    }
  }
}
