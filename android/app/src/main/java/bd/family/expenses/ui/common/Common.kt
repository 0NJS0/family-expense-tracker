package bd.family.expenses.ui.common

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import bd.family.expenses.R
import bd.family.expenses.money.Taka
import bd.family.expenses.ui.theme.TouchTarget
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** True when the app is currently shown in Bangla. */
fun isBangla(): Boolean {
  val tags = AppCompatDelegate.getApplicationLocales().toLanguageTags()
  return tags.isEmpty() || tags.startsWith("bn")
}

@Composable
fun money(poisha: Long, showSign: Boolean = false): String = Taka.format(poisha, isBangla(), showSign)

fun formatDateTime(epochMs: Long, zone: String): String {
  val locale = if (isBangla()) Locale.forLanguageTag("bn-BD") else Locale.UK
  val text =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
      .withLocale(locale)
      .format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.of(zone)))
  return if (isBangla()) Taka.toBanglaDigits(text) else text
}

fun formatDate(epochMs: Long, zone: String): String {
  val locale = if (isBangla()) Locale.forLanguageTag("bn-BD") else Locale.UK
  val text =
    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
      .withLocale(locale)
      .format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.of(zone)))
  return if (isBangla()) Taka.toBanglaDigits(text) else text
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreenBar(title: String, onBack: (() -> Unit)?) {
  TopAppBar(
    title = { Text(title, style = MaterialTheme.typography.headlineSmall) },
    navigationIcon = {
      if (onBack != null) {
        IconButton(onClick = onBack, modifier = Modifier.heightIn(min = TouchTarget)) {
          Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
        }
      }
    },
    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
  )
}

@Composable
fun BigButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
  Button(
    onClick = onClick,
    enabled = enabled,
    modifier = modifier.fillMaxWidth().heightIn(min = 72.dp),
  ) {
    Text(text, style = MaterialTheme.typography.titleLarge)
  }
}

@Composable
fun LabeledRow(label: String, value: String) {
  Row(
    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(label, style = MaterialTheme.typography.bodyLarge)
    Text(value, style = MaterialTheme.typography.titleLarge)
  }
}
