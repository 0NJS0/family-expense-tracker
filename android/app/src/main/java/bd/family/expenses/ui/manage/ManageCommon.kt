package bd.family.expenses.ui.manage

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import bd.family.expenses.R
import bd.family.expenses.data.remote.ApiException
import bd.family.expenses.data.remote.NetworkException
import bd.family.expenses.ui.theme.TouchTarget
import kotlin.coroutines.cancellation.CancellationException

/** State of an online request on the management screens. */
sealed interface Load<out T> {
  data object Loading : Load<Nothing>

  data class Failed(val error: Throwable) : Load<Nothing>

  data class Ready<T>(val value: T) : Load<T>
}

class Loader<T>(val state: Load<T>, val reload: () -> Unit)

/** Runs [block] when [keys] change or [Loader.reload] is called. */
@Composable
fun <T> rememberLoader(vararg keys: Any?, block: suspend () -> T): Loader<T> {
  var tick by remember { mutableIntStateOf(0) }
  var state by remember { mutableStateOf<Load<T>>(Load.Loading) }
  LaunchedEffect(*keys, tick) {
    state = Load.Loading
    state =
      try {
        Load.Ready(block())
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Load.Failed(e)
      }
  }
  return Loader(state) { tick++ }
}

/** A plain-language message for an error from the server or network. */
@Composable
fun errorMessage(error: Throwable): String =
  when {
    error is NetworkException -> stringResource(R.string.need_internet)
    error is ApiException && error.status == 403 -> stringResource(R.string.no_permission)
    error is ApiException && error.status == 409 && error.message.contains("admin", ignoreCase = true) ->
      stringResource(R.string.last_admin)
    error is ApiException && error.status == 409 && error.message.contains("name", ignoreCase = true) ->
      stringResource(R.string.name_exists)
    error is ApiException && error.status == 409 -> stringResource(R.string.changed_elsewhere)
    error is ApiException -> error.message
    else -> stringResource(R.string.need_internet)
  }

@Composable
fun ErrorBox(error: Throwable, onRetry: (() -> Unit)? = null) {
  Card(
    modifier = Modifier.fillMaxWidth(),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
  ) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Text(
        "⚠ " + errorMessage(error),
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
      )
      onRetry?.let {
        OutlinedButton(onClick = it, modifier = Modifier.heightIn(min = TouchTarget)) {
          Text(stringResource(R.string.try_again))
        }
      }
    }
  }
}

/** Shows loading / error / content for a [Loader]. */
@Composable
fun <T> LoadView(loader: Loader<T>, content: @Composable (T) -> Unit) {
  when (val s = loader.state) {
    Load.Loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
    is Load.Failed -> ErrorBox(s.error, loader.reload)
    is Load.Ready -> content(s.value)
  }
}

@Composable
fun SectionCard(title: String, content: @Composable () -> Unit) {
  Card(modifier = Modifier.fillMaxWidth()) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      Text(title, style = MaterialTheme.typography.titleLarge)
      content()
    }
  }
}

/** One labelled bar for a share of a total (display only; amounts come from the server). */
@Composable
fun ShareBar(label: String, amount: String, fraction: Float) {
  Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
      Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
      Text(amount, style = MaterialTheme.typography.titleMedium)
    }
    LinearProgressIndicator(progress = { fraction.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().heightIn(min = 8.dp))
  }
}

@Composable
fun SmallButton(text: String, onClick: () -> Unit, primary: Boolean = false, enabled: Boolean = true) {
  if (primary) {
    Button(onClick = onClick, enabled = enabled, modifier = Modifier.heightIn(min = TouchTarget)) { Text(text) }
  } else {
    OutlinedButton(onClick = onClick, enabled = enabled, modifier = Modifier.heightIn(min = TouchTarget)) { Text(text) }
  }
}
