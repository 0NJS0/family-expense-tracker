package bd.family.expenses.ui.login

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import bd.family.expenses.AppContainer
import bd.family.expenses.BuildConfig
import bd.family.expenses.R
import bd.family.expenses.data.remote.LoginResult
import bd.family.expenses.ui.common.BigButton
import kotlinx.coroutines.launch

@Composable
fun LoginScreen(container: AppContainer, sessionEnded: Boolean) {
  val scope = rememberCoroutineScope()
  var server by rememberSaveable { mutableStateOf(container.settings.lastServerUrl ?: "") }
  var username by rememberSaveable { mutableStateOf("") }
  var password by rememberSaveable { mutableStateOf("") }
  var busy by rememberSaveable { mutableStateOf(false) }
  var error by rememberSaveable { mutableStateOf<Int?>(null) }
  var errorDetail by rememberSaveable { mutableStateOf("") }

  fun submit() {
    if (busy) return
    busy = true
    error = null
    scope.launch {
      val previous = container.settings.profile.value
      val result =
        container.session.login(server, username, password) { userId ->
          // Never let another person's unsent entries be uploaded under a new login.
          previous == null || previous.userId == userId || container.database.transactions().unsyncedCount() == 0
        }
      busy = false
      when (result) {
        is LoginResult.Success -> {
          password = ""
          if (previous != null && previous.userId != result.profile.userId) {
            // A different person: forget the previous person's cached data.
            container.database.transactions().deleteAll()
            container.database.changes().deleteAll()
            container.database.receipts().deleteAll()
          }
          container.scheduler.schedulePeriodic()
          container.scheduler.syncNow()
        }
        LoginResult.InvalidServerUrl ->
          error = if (BuildConfig.DEBUG) R.string.error_server_url else R.string.error_server_url_https
        LoginResult.WrongCredentials -> error = R.string.error_credentials
        LoginResult.NoHousehold -> error = R.string.error_no_household
        LoginResult.OtherUserHasUnsentEntries -> error = R.string.error_other_user
        LoginResult.TooManyAttempts -> error = R.string.error_too_many
        LoginResult.Unreachable -> error = R.string.error_unreachable
        is LoginResult.ServerError -> {
          error = R.string.error_server
          errorDetail = result.message
        }
      }
    }
  }

  Column(
    modifier =
      Modifier.fillMaxSize().safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
    Text(stringResource(R.string.login_title), style = MaterialTheme.typography.titleLarge)
    if (sessionEnded) {
      Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Text(stringResource(R.string.session_ended), modifier = Modifier.padding(16.dp))
      }
    }
    OutlinedTextField(
      value = server,
      onValueChange = { server = it },
      label = { Text(stringResource(R.string.server_address)) },
      supportingText = { Text(stringResource(R.string.server_address_hint)) },
      singleLine = true,
      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
      modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
      value = username,
      onValueChange = { username = it },
      label = { Text(stringResource(R.string.username)) },
      singleLine = true,
      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Next),
      modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
      value = password,
      onValueChange = { password = it },
      label = { Text(stringResource(R.string.password)) },
      singleLine = true,
      visualTransformation = PasswordVisualTransformation(),
      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
      modifier = Modifier.fillMaxWidth(),
    )
    error?.let {
      Text(
        if (it == R.string.error_server) stringResource(it, errorDetail) else stringResource(it),
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
      )
    }
    BigButton(
      text = stringResource(if (busy) R.string.signing_in else R.string.sign_in),
      onClick = ::submit,
      enabled = !busy && server.isNotBlank() && username.isNotBlank() && password.isNotEmpty(),
    )
  }
}
