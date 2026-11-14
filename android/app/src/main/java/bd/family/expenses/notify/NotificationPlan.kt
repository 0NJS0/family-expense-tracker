package bd.family.expenses.notify

import bd.family.expenses.data.remote.BudgetAlertDto
import bd.family.expenses.data.remote.NotificationPrefsDto
import bd.family.expenses.data.remote.OccurrenceDto
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** What to show after a sync: only items not shown before, only if the person wants them. */
data class SyncNotifications(val due: List<OccurrenceDto>, val alerts: List<BudgetAlertDto>, val seen: Set<String>)

object NotificationPlan {
  /** Remembered keys are capped; old ones can safely be forgotten once gone from the server. */
  private const val MAX_SEEN = 300

  fun afterSync(
    prefs: NotificationPrefsDto,
    isAdmin: Boolean,
    due: List<OccurrenceDto>,
    alerts: List<BudgetAlertDto>,
    seen: Set<String>,
  ): SyncNotifications {
    val newDue = if (prefs.recurringDue) due.filter { "due:${it.id}" !in seen } else emptyList()
    val newAlerts = if (prefs.budgetAlerts && isAdmin) alerts.filter { it.key !in seen } else emptyList()
    // Everything current counts as seen, even if not shown (switched off): turning
    // the setting on later must not bring a flood of old items.
    val current = due.map { "due:${it.id}" } + alerts.map { it.key }
    val keep = (seen.filter { it in current } + current).distinct().takeLast(MAX_SEEN).toSet()
    return SyncNotifications(newDue, newAlerts, keep)
  }

  enum class Reminder { DAILY, MISSING_ENTRY }

  /**
   * The evening reminder: at most one a day. A daily reminder always shows; the
   * missing-entry reminder only when nothing was entered today.
   */
  fun reminder(prefs: NotificationPrefsDto, today: LocalDate, lastReminded: String?, enteredToday: Boolean): Reminder? =
    when {
      lastReminded == today.toString() -> null
      prefs.dailyReminder -> Reminder.DAILY
      prefs.missingEntryReminder && !enteredToday -> Reminder.MISSING_ENTRY
      else -> null
    }

  /** The next reminder time strictly after [now], in the household timezone. */
  fun nextReminderAt(now: Instant, zone: ZoneId, time: LocalTime): Instant {
    val local = ZonedDateTime.ofInstant(now, zone)
    var candidate = local.toLocalDate().atTime(time).atZone(zone)
    if (!candidate.toInstant().isAfter(now)) candidate = local.toLocalDate().plusDays(1).atTime(time).atZone(zone)
    return candidate.toInstant()
  }

  fun parseTime(text: String): LocalTime = runCatching { LocalTime.parse(text) }.getOrDefault(LocalTime.of(20, 0))

  fun wantsReminders(prefs: NotificationPrefsDto) = prefs.dailyReminder || prefs.missingEntryReminder
}
