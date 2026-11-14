package bd.family.expenses.ui.history

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import bd.family.expenses.AppContainer
import bd.family.expenses.R
import bd.family.expenses.data.local.SyncState
import bd.family.expenses.ui.theme.TouchTarget
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Receipt photos of one entry: take one (works offline) and see whether it was sent. */
@Composable
fun ReceiptSection(container: AppContainer, clientId: String, canAdd: Boolean) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val items by remember(clientId) { container.database.receipts().observeFor(clientId) }.collectAsStateWithLifecycle(emptyList())
  // Survives the trip to the camera app (and a possible process restart).
  var photoPath by rememberSaveable { mutableStateOf<String?>(null) }
  var problem by remember { mutableStateOf(false) }
  val camera =
    rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
      val path = photoPath ?: return@rememberLauncherForActivityResult
      photoPath = null
      if (!taken) {
        File(path).delete()
        return@rememberLauncherForActivityResult
      }
      scope.launch {
        problem =
          runCatching { withContext(Dispatchers.Default) { container.receipts.addPhoto(clientId, File(path)) } }.isFailure
      }
    }

  if (items.isNotEmpty() || canAdd) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Text(stringResource(R.string.receipts_title), style = MaterialTheme.typography.titleMedium)
    items.forEachIndexed { index, item ->
      Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        val state =
          when (item.state) {
            SyncState.SYNCED -> "✓ " + stringResource(R.string.receipt_sent)
            SyncState.FAILED -> "⚠ " + stringResource(R.string.receipt_failed) + (item.lastError?.let { ": $it" } ?: "")
            else -> "⏳ " + stringResource(R.string.receipt_waiting)
          }
        Text("🧾 ${index + 1}. $state", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        if (item.state != SyncState.SYNCED) {
          TextButton(
            onClick = { scope.launch { container.receipts.discard(item) } },
            modifier = Modifier.heightIn(min = TouchTarget),
          ) { Text(stringResource(R.string.receipt_remove)) }
        }
      }
    }
    if (canAdd) {
      OutlinedButton(
        onClick = {
          val dir = File(context.cacheDir, "camera").apply { mkdirs() }
          val file = File(dir, UUID.randomUUID().toString() + ".jpg")
          photoPath = file.absolutePath
          camera.launch(FileProvider.getUriForFile(context, context.packageName + ".camera", file))
        },
        modifier = Modifier.fillMaxWidth().heightIn(min = TouchTarget),
      ) { Text("📷 " + stringResource(R.string.receipt_take), style = MaterialTheme.typography.titleMedium) }
    }
    if (problem) Text("⚠ " + stringResource(R.string.receipt_photo_problem), color = MaterialTheme.colorScheme.error)
  }
}
