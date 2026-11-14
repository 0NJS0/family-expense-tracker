package bd.family.expenses.notify

import bd.family.expenses.data.remote.BudgetAlertDto
import bd.family.expenses.data.remote.NotificationPrefsDto
import bd.family.expenses.data.remote.OccurrenceDto
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationPlanTest {
  private val dhaka = ZoneId.of("Asia/Dhaka")
  private val today = LocalDate.of(2026, 10, 17)
  private fun due(id: String) = OccurrenceDto(id, "Rent", "expense", 1_500_000, "2026-10-01", "pending")
  private fun alert(t: Int) = BudgetAlertDto(null, null, null, "2026-10-01", t, 100, 90)

  @Test
  fun eachItemIsShownOnce() {
    val first = NotificationPlan.afterSync(NotificationPrefsDto(), isAdmin = true, listOf(due("a")), listOf(alert(80)), emptySet())
    assertEquals(listOf("a"), first.due.map { it.id })
    assertEquals(1, first.alerts.size)
    val second = NotificationPlan.afterSync(NotificationPrefsDto(), true, listOf(due("a"), due("b")), listOf(alert(80)), first.seen)
    assertEquals(listOf("b"), second.due.map { it.id })
    assertTrue(second.alerts.isEmpty())
  }

  @Test
  fun switchedOffItemsAreNotShownNorLaterFlooded() {
    val off = NotificationPrefsDto(recurringDue = false, budgetAlerts = false)
    val plan = NotificationPlan.afterSync(off, true, listOf(due("a")), listOf(alert(80)), emptySet())
    assertTrue(plan.due.isEmpty() && plan.alerts.isEmpty())
    // Turning them on later does not bring back what was already there.
    val later = NotificationPlan.afterSync(NotificationPrefsDto(), true, listOf(due("a")), listOf(alert(80)), plan.seen)
    assertTrue(later.due.isEmpty() && later.alerts.isEmpty())
  }

  @Test
  fun budgetAlertsAreForAdminsOnly() {
    assertTrue(NotificationPlan.afterSync(NotificationPrefsDto(), isAdmin = false, emptyList(), listOf(alert(80)), emptySet()).alerts.isEmpty())
  }

  @Test
  fun atMostOneReminderADayAndMissingOnlyWhenNothingWasEntered() {
    val defaults = NotificationPrefsDto() // missing-entry on, daily off
    assertEquals(NotificationPlan.Reminder.MISSING_ENTRY, NotificationPlan.reminder(defaults, today, null, enteredToday = false))
    assertNull(NotificationPlan.reminder(defaults, today, null, enteredToday = true))
    assertNull(NotificationPlan.reminder(defaults, today, "2026-10-17", enteredToday = false))
    val daily = NotificationPrefsDto(dailyReminder = true)
    assertEquals(NotificationPlan.Reminder.DAILY, NotificationPlan.reminder(daily, today, "2026-10-16", enteredToday = true))
    assertNull(NotificationPlan.reminder(NotificationPrefsDto(missingEntryReminder = false), today, null, false))
  }

  @Test
  fun nextReminderIsInTheHouseholdTimezone() {
    val eight = LocalTime.of(20, 0)
    // 13:00 UTC = 19:00 in Dhaka → today 20:00 Dhaka = 14:00 UTC.
    assertEquals(Instant.parse("2026-10-17T14:00:00Z"), NotificationPlan.nextReminderAt(Instant.parse("2026-10-17T13:00:00Z"), dhaka, eight))
    // Exactly at, or after, the time → tomorrow.
    assertEquals(Instant.parse("2026-10-18T14:00:00Z"), NotificationPlan.nextReminderAt(Instant.parse("2026-10-17T14:00:00Z"), dhaka, eight))
    assertEquals(LocalTime.of(20, 0), NotificationPlan.parseTime("nonsense"))
    assertEquals(LocalTime.of(19, 30), NotificationPlan.parseTime("19:30:00"))
  }
}
