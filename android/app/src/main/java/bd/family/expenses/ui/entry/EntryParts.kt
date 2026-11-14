package bd.family.expenses.ui.entry

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import bd.family.expenses.R
import bd.family.expenses.money.Taka
import bd.family.expenses.ui.common.BigButton
import bd.family.expenses.ui.common.formatDate
import bd.family.expenses.ui.common.isBangla
import bd.family.expenses.ui.theme.TouchTarget
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

// Building blocks shared by the expense, income and transfer screens.

private val QUICK_AMOUNTS = listOf("50", "100", "500", "1000")

/** The large amount field with a clear button and one-tap common amounts. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AmountInput(amountText: String, error: Int?, onChange: (String) -> Unit) {
  OutlinedTextField(
    value = amountText,
    onValueChange = { onChange(sanitiseAmountInput(it)) },
    label = { Text(stringResource(R.string.amount)) },
    prefix = { Text("৳", style = MaterialTheme.typography.displaySmall) },
    textStyle = MaterialTheme.typography.displaySmall.copy(fontSize = 40.sp),
    singleLine = true,
    isError = error != null,
    supportingText = error?.let { { Text(stringResource(it)) } },
    trailingIcon = {
      if (amountText.isNotEmpty()) {
        TextButton(onClick = { onChange("") }, modifier = Modifier.heightIn(min = TouchTarget)) {
          val clearLabel = stringResource(R.string.clear_amount)
          Text("✕", modifier = Modifier.semantics { contentDescription = clearLabel })
        }
      }
    },
    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
    modifier = Modifier.fillMaxWidth(),
  )
  // Common amounts in one tap; the field can still be edited afterwards.
  val bangla = isBangla()
  FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    QUICK_AMOUNTS.forEach { taka ->
      val label = if (bangla) Taka.toBanglaDigits(taka) else taka
      Choice("৳$label", amountText == taka, onClick = { onChange(taka) })
    }
  }
}

/** Today / Yesterday / another (past) day, with a date picker for the last one. */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun DateInput(date: LocalDate, today: LocalDate, zone: ZoneId, onChange: (LocalDate) -> Unit) {
  var showPicker by remember { mutableStateOf(false) }
  Section(stringResource(R.string.date), null)
  FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Choice(stringResource(R.string.today), date == today, onClick = { onChange(today) })
    Choice(stringResource(R.string.yesterday), date == today.minusDays(1), onClick = { onChange(today.minusDays(1)) })
    Choice(
      if (date.isBefore(today.minusDays(1))) formatDate(date.atStartOfDay(zone).toInstant().toEpochMilli(), zone.id)
      else stringResource(R.string.other_day),
      date.isBefore(today.minusDays(1)),
      onClick = { showPicker = true },
    )
  }
  if (showPicker) {
    val state =
      rememberDatePickerState(
        initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        selectableDates =
          object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long): Boolean =
              !Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate().isAfter(today)
          },
      )
    DatePickerDialog(
      onDismissRequest = { showPicker = false },
      confirmButton = {
        TextButton(onClick = {
          state.selectedDateMillis?.let { onChange(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
          showPicker = false
        }) { Text(stringResource(R.string.ok)) }
      },
      dismissButton = { TextButton(onClick = { showPicker = false }) { Text(stringResource(R.string.cancel)) } },
    ) { DatePicker(state = state) }
  }
}

/** Save is pinned to the bottom, with a reminder next to it when something needs fixing. */
@Composable
internal fun SaveBar(hasErrors: Boolean, enabled: Boolean, onSave: () -> Unit) {
  Surface(shadowElevation = 8.dp, color = MaterialTheme.colorScheme.background) {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
      if (hasErrors) {
        // Also say so right next to the button, where the person is looking.
        Text(
          "⚠ " + stringResource(R.string.check_fields),
          color = MaterialTheme.colorScheme.error,
          style = MaterialTheme.typography.titleMedium,
          modifier = Modifier.padding(bottom = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
        )
      }
      BigButton(text = stringResource(R.string.save), enabled = enabled, onClick = onSave)
    }
  }
}

/** A clear confirmation the person must acknowledge, rather than a brief toast. */
@Composable
internal fun SavedDialog(onDone: () -> Unit) {
  AlertDialog(
    onDismissRequest = onDone,
    confirmButton = {
      TextButton(onClick = onDone, modifier = Modifier.heightIn(min = TouchTarget)) {
        Text(stringResource(R.string.ok), style = MaterialTheme.typography.titleLarge)
      }
    },
    text = { Text("✓ " + stringResource(R.string.saved_offline), style = MaterialTheme.typography.titleMedium) },
  )
}

@Composable
internal fun Section(title: String, error: Int?) {
  Column {
    Text(title, style = MaterialTheme.typography.titleMedium)
    error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
  }
}

/** A large selectable option. Selection is shown by a check mark and border, not colour alone. */
@Composable
internal fun Choice(label: String, selected: Boolean, onClick: () -> Unit) {
  val selectedText = stringResource(R.string.selected)
  OutlinedButton(
    onClick = onClick,
    border =
      BorderStroke(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline),
    colors =
      if (selected) ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
      else ButtonDefaults.outlinedButtonColors(),
    modifier =
      Modifier.heightIn(min = TouchTarget).semantics {
        this.selected = selected
        role = Role.RadioButton
        if (selected) contentDescription = "$label, $selectedText"
      },
  ) {
    Text((if (selected) "✓ " else "") + label, style = MaterialTheme.typography.titleMedium)
  }
}
