package bd.family.expenses.data

import bd.family.expenses.data.local.SyncState
import bd.family.expenses.sync.FakeSettings
import bd.family.expenses.sync.FakeTransactionDao
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EntryRepositoryTest {
  private val dao = FakeTransactionDao()
  private val settings = FakeSettings()
  private var syncRequests = 0
  // 18:30 UTC on 15 Oct = 00:30 on 16 Oct in Dhaka.
  private val clock = Clock.fixed(Instant.parse("2026-10-15T18:30:00Z"), ZoneOffset.UTC)
  private var ids = 0
  private val repo = EntryRepository(dao, settings, { syncRequests++ }, clock, newId = { "id-${++ids}" })

  private fun entry(date: LocalDate, attributed: String? = null) =
    NewEntry("expense", 12050, "cat-groc", "acc-cash", date, "  rice  ", attributed)

  @Test
  fun savesLocallyFirstAndRequestsUpload() = runTest {
    val saved = repo.save(entry(LocalDate.of(2026, 10, 16)))
    assertEquals(SyncState.PENDING, dao.rows.getValue("id-1").syncState)
    assertEquals(1, syncRequests)
    assertEquals("rice", saved.note)
    assertEquals("u-amma", saved.createdByUserId)
    assertEquals("acc-cash", settings.lastAccountId)
  }

  @Test
  fun todayUsesTheCurrentTimeInTheHouseholdTimezone() = runTest {
    val saved = repo.save(entry(LocalDate.of(2026, 10, 16)))
    assertEquals("2026-10-16T00:30:00+06:00", saved.occurredAt)
    assertEquals(Instant.parse("2026-10-15T18:30:00Z").toEpochMilli(), saved.occurredAtEpochMs)
  }

  @Test
  fun anEarlierDayIsStoredAtMiddayLocalTime() = runTest {
    val saved = repo.save(entry(LocalDate.of(2026, 10, 14)))
    assertEquals("2026-10-14T12:00:00+06:00", saved.occurredAt)
  }

  @Test
  fun attributionToSelfIsLeftToTheServerDefault() = runTest {
    assertNull(repo.save(entry(LocalDate.of(2026, 10, 16), attributed = "u-amma")).attributedUserId)
    assertEquals("u-abba", repo.save(entry(LocalDate.of(2026, 10, 16), attributed = "u-abba")).attributedUserId)
  }

  @Test
  fun everySaveGetsItsOwnClientId() = runTest {
    repo.save(entry(LocalDate.of(2026, 10, 16)))
    repo.save(entry(LocalDate.of(2026, 10, 16)))
    assertEquals(setOf("id-1", "id-2"), dao.rows.keys)
  }

  @Test
  fun transferIsSavedOfflineWithBothAccountsAndNoCategory() = runTest {
    settings.lastAccountId = "acc-cash"
    val saved =
      repo.save(NewEntry("transfer", 50000, null, "acc-cash", LocalDate.of(2026, 10, 16), null, null, destinationAccountId = "acc-bkash"))
    assertEquals(SyncState.PENDING, saved.syncState)
    assertEquals("transfer", saved.type)
    assertNull(saved.categoryId)
    assertEquals("acc-bkash", saved.destinationAccountId)
    assertEquals(1, syncRequests)
    // A transfer does not change the account pre-selected for expenses.
    assertEquals("acc-cash", settings.lastAccountId)
  }

  @Test
  fun malformedTransfersAreRefusedBeforeSaving() = runTest {
    val day = LocalDate.of(2026, 10, 16)
    listOf(
      NewEntry("transfer", 100, null, "acc-cash", day, null, null, destinationAccountId = "acc-cash"),
      NewEntry("transfer", 100, "cat-groc", "acc-cash", day, null, null, destinationAccountId = "acc-bkash"),
      NewEntry("expense", 100, "cat-groc", "acc-cash", day, null, null, destinationAccountId = "acc-bkash"),
      NewEntry("expense", 100, null, "acc-cash", day, null, null),
    ).forEach { bad ->
      try {
        repo.save(bad)
        org.junit.Assert.fail("accepted $bad")
      } catch (expected: IllegalArgumentException) {}
    }
    assertEquals(0, dao.rows.size)
  }
}
