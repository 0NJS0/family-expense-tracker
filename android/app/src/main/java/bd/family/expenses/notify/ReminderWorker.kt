package bd.family.expenses.notify

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import bd.family.expenses.FamilyExpensesApp
import bd.family.expenses.data.remote.NotificationPrefsDto
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * The evening reminder. Runs once at the chosen time (household timezone),
 * decides whether to remind (see [NotificationPlan.reminder]) and schedules the
 * next one. Android may delay it a little (battery saving); that is acceptable
 * for a reminder, so no exact alarm permission is needed.
 */
class ReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
  override suspend fun doWork(): Result {
    val container = (applicationContext as FamilyExpensesApp).container
    val profile = container.settings.profile.value ?: return Result.success()
    val prefs = container.settings.notificationPrefs
    val zone = ZoneId.of(profile.timezone)
    val today = LocalDate.now(zone)
    val start = today.atStartOfDay(zone).toInstant().toEpochMilli()
    val end = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    val entered = container.database.transactions().countEnteredBetween(profile.householdId, profile.userId, start, end) > 0
    NotificationPlan.reminder(prefs, today, container.settings.lastReminderDate, entered)?.let {
      container.notifier.reminder(it)
      container.settings.lastReminderDate = today.toString()
    }
    container.reminders.schedule(prefs, profile.timezone)
    return Result.success()
  }
}

class ReminderScheduler(private val workManager: WorkManager) {
  /** (Re)plan the next reminder, or cancel it when both reminders are off. */
  fun schedule(prefs: NotificationPrefsDto, timezone: String, now: Instant = Instant.now()) {
    if (!NotificationPlan.wantsReminders(prefs)) {
      workManager.cancelUniqueWork(UNIQUE)
      return
    }
    val at = NotificationPlan.nextReminderAt(now, ZoneId.of(timezone), NotificationPlan.parseTime(prefs.reminderTime))
    val request =
      OneTimeWorkRequestBuilder<ReminderWorker>()
        .setInitialDelay(at.toEpochMilli() - now.toEpochMilli(), TimeUnit.MILLISECONDS)
        .build()
    workManager.enqueueUniqueWork(UNIQUE, ExistingWorkPolicy.REPLACE, request)
  }

  fun cancel() = workManager.cancelUniqueWork(UNIQUE)

  private companion object {
    const val UNIQUE = "reminder"
  }
}
