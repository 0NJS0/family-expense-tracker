package bd.family.expenses.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import bd.family.expenses.R
import bd.family.expenses.data.remote.BudgetAlertDto
import bd.family.expenses.data.remote.OccurrenceDto
import bd.family.expenses.money.Taka
import bd.family.expenses.ui.MainActivity

/** Called by the sync engine with what is new; the Android implementation posts notifications. */
interface SyncNotifier {
  fun show(items: SyncNotifications)

  object None : SyncNotifier {
    override fun show(items: SyncNotifications) {}
  }
}

/**
 * Posts the app's few notifications. Everything stays on the phone: there is
 * no push service; reminders are scheduled locally and other items come from
 * the regular sync.
 */
class Notifier(private val context: Context) : SyncNotifier {
  init {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val manager = context.getSystemService(NotificationManager::class.java)
      manager.createNotificationChannel(
        NotificationChannel(CHANNEL, context.getString(R.string.notify_channel), NotificationManager.IMPORTANCE_DEFAULT)
      )
    }
  }

  private fun allowed(): Boolean =
    NotificationManagerCompat.from(context).areNotificationsEnabled() &&
      (Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)

  private fun post(id: Int, title: String, text: String) {
    if (!allowed()) return
    val open =
      PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE,
      )
    val notification =
      NotificationCompat.Builder(context, CHANNEL)
        .setSmallIcon(R.drawable.ic_launcher_foreground)
        .setContentTitle(title)
        .setContentText(text)
        .setStyle(NotificationCompat.BigTextStyle().bigText(text))
        .setContentIntent(open)
        .setAutoCancel(true)
        .build()
    try {
      NotificationManagerCompat.from(context).notify(id, notification)
    } catch (e: SecurityException) {
      // Permission withdrawn meanwhile: nothing to show.
    }
  }

  private fun bangla() = context.resources.configuration.locales[0].language == "bn"

  private fun taka(poisha: Long) = Taka.format(poisha, bangla())

  fun reminder(kind: NotificationPlan.Reminder) {
    val text =
      context.getString(if (kind == NotificationPlan.Reminder.DAILY) R.string.notify_daily else R.string.notify_missing)
    post(ID_REMINDER, context.getString(R.string.notify_reminder_title), text)
  }

  override fun show(items: SyncNotifications) {
    if (items.due.isNotEmpty()) {
      val text = items.due.joinToString("\n") { it.ruleName + " — " + taka(it.amountPoisha) }
      post(ID_DUE, context.getString(R.string.due_title), text)
    }
    if (items.alerts.isNotEmpty()) {
      val text =
        items.alerts.joinToString("\n") { a ->
          val name = if (a.categoryId == null) context.getString(R.string.budget_overall) else (if (bangla()) a.nameBn else a.nameEn) ?: ""
          context.getString(
            if (a.thresholdPercent >= 100) R.string.notify_budget_reached else R.string.notify_budget_warning,
            name,
            if (bangla()) Taka.toBanglaDigits(a.thresholdPercent.toString()) else a.thresholdPercent.toString(),
            taka(a.spentPoisha),
            taka(a.budgetPoisha),
          )
        }
      post(ID_BUDGET, context.getString(R.string.notify_budget_title), text)
    }
  }

  private companion object {
    const val CHANNEL = "reminders"
    const val ID_REMINDER = 1
    const val ID_DUE = 2
    const val ID_BUDGET = 3
  }
}

