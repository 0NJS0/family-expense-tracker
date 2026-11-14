package bd.family.expenses.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import bd.family.expenses.FamilyExpensesApp
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
  override suspend fun doWork(): Result {
    val engine = (applicationContext as FamilyExpensesApp).container.syncEngine
    return when (engine.run()) {
      is SyncOutcome.Done, SyncOutcome.LoginRequired, SyncOutcome.NoSession -> Result.success()
      // Bounded retries with exponential backoff. After that, entries simply
      // stay pending until the next save, app start or periodic sync.
      is SyncOutcome.Retry -> if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.success()
    }
  }

  companion object {
    const val MAX_ATTEMPTS = 8
  }
}

class SyncScheduler(private val workManager: WorkManager) {
  private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

  /** Upload as soon as there is a network; queued behind any running sync. */
  fun syncNow() {
    val request =
      OneTimeWorkRequestBuilder<SyncWorker>()
        .setConstraints(online)
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
        .build()
    workManager.enqueueUniqueWork(UNIQUE, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
  }

  /** Safety net: also try every few hours while the app is not used. */
  fun schedulePeriodic() {
    val request =
      PeriodicWorkRequestBuilder<SyncWorker>(6, TimeUnit.HOURS)
        .setConstraints(online)
        .build()
    workManager.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
  }

  fun cancelAll() {
    workManager.cancelUniqueWork(UNIQUE)
    workManager.cancelUniqueWork(PERIODIC)
  }

  val isRunning: Flow<Boolean> =
    workManager.getWorkInfosForUniqueWorkFlow(UNIQUE).map { infos -> infos.any { it.state == WorkInfo.State.RUNNING } }

  private companion object {
    const val UNIQUE = "sync"
    const val PERIODIC = "sync-periodic"
  }
}
