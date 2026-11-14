package bd.family.expenses.data

import bd.family.expenses.data.local.ChangeEntity
import bd.family.expenses.data.local.SyncState
import bd.family.expenses.data.local.TransactionWithChange
import bd.family.expenses.data.local.withChange
import bd.family.expenses.data.remote.TransactionDto
import bd.family.expenses.data.remote.json
import bd.family.expenses.sync.FakeChangeDao
import bd.family.expenses.sync.FakeSettings
import bd.family.expenses.sync.FakeTransactionDao
import bd.family.expenses.sync.PROFILE
import bd.family.expenses.sync.pendingRow
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ChangeRepositoryTest {
  private val dao = FakeTransactionDao()
  private val changes = FakeChangeDao(dao)
  private val settings = FakeSettings()
  private var syncRequests = 0
  // 04:00 UTC on 16 Oct = 10:00 in Dhaka.
  private val clock = Clock.fixed(Instant.parse("2026-10-16T04:00:00Z"), ZoneOffset.UTC)
  private val repo = ChangeRepository(dao, changes, settings, { syncRequests++ }, clock, newId = { "new-id" })

  // pendingRow: expense ৳120.50, Groceries, Cash, 15 Oct 18:30 Dhaka.
  private val day = LocalDate.of(2026, 10, 15)

  private suspend fun synced(id: String = "a", version: Int = 3, createdBy: String = PROFILE.userId) =
    pendingRow(id, createdBy = createdBy).copy(syncState = SyncState.SYNCED, serverId = "srv-$id", serverVersion = version).also { dao.insert(it) }

  private fun same(amount: Long = 12050, note: String? = null, date: LocalDate = day, category: String = "cat-groc", account: String = "acc-cash") =
    EntryEdit(amount, category, account, null, date, note)

  private inline fun refused(block: () -> Unit) {
    try {
      block()
      fail("expected refusal")
    } catch (expected: IllegalStateException) {
    } catch (expected: IllegalArgumentException) {
    }
  }

  @Test
  fun correctionKeepsOnlyChangedFieldsAndTheVersionSeen() = runTest {
    synced(version = 3)
    assertTrue(repo.edit("a", same(amount = 15000)))
    val c = changes.changes.getValue("a")
    assertEquals(3, c.baseVersion)
    assertEquals(15000L, c.amountPoisha)
    assertNull(c.categoryId)
    assertNull(c.accountId)
    assertNull(c.occurredAt)
    assertFalse(c.noteChanged)
    assertEquals(1, syncRequests)
    // The stored row is still the server's; the change is shown on top of it.
    assertEquals(12050L, dao.rows.getValue("a").amountPoisha)
    assertEquals(15000L, TransactionWithChange(dao.rows.getValue("a"), c).shown.amountPoisha)
  }

  @Test
  fun secondCorrectionIsMergedAndChangingBackCancelsIt() = runTest {
    synced(version = 3)
    repo.edit("a", same(amount = 15000))
    val first = changes.changes.getValue("a")
    repo.edit("a", same(amount = 15000, note = "rice"))
    val second = changes.changes.getValue("a")
    assertEquals(first.id, second.id)
    assertEquals(3, second.baseVersion)
    assertEquals("rice", second.note)
    assertTrue(second.noteChanged)
    // Back to exactly what the server has: nothing left to send.
    assertFalse(repo.edit("a", same()))
    assertTrue(changes.changes.isEmpty())
  }

  @Test
  fun aNewDayIsStoredAtMiddayAndTheSameDayKeepsTheTime() = runTest {
    synced()
    repo.edit("a", same(date = LocalDate.of(2026, 10, 12)))
    assertEquals("2026-10-12T12:00:00+06:00", changes.changes.getValue("a").occurredAt)
    repo.edit("a", same(amount = 1))
    assertNull(changes.changes.getValue("a").occurredAt)
  }

  @Test
  fun anEntryNotYetSentIsCorrectedAfterItsCreationAsVersionOne() = runTest {
    dao.insert(pendingRow("p"))
    repo.edit("p", same(amount = 999))
    assertEquals(1, changes.changes.getValue("p").baseVersion)
    // The original stays queued exactly as first saved, so a lost reply can never cause a 409 on create.
    assertEquals(12050L, dao.rows.getValue("p").amountPoisha)
  }

  @Test
  fun aRejectedEntryIsCorrectedInPlaceAndQueuedAgain() = runTest {
    dao.insert(pendingRow("f").copy(syncState = SyncState.FAILED, lastError = "category archived", attempts = 2))
    repo.edit("f", same(amount = 500, category = "cat-other"))
    val row = dao.rows.getValue("f")
    assertEquals(SyncState.PENDING, row.syncState)
    assertEquals(500L, row.amountPoisha)
    assertEquals("cat-other", row.categoryId)
    assertNull(row.lastError)
    assertEquals(0, row.attempts)
    assertTrue(changes.changes.isEmpty())
  }

  @Test
  fun voidReplacesAWaitingCorrectionAndKeepsItsVersion() = runTest {
    synced(version = 2)
    repo.edit("a", same(amount = 1))
    repo.void("a", "  Entered twice ")
    val c = changes.changes.getValue("a")
    assertTrue(c.isVoid)
    assertEquals(2, c.baseVersion)
    assertEquals("Entered twice", c.voidReason)
    assertNull(c.amountPoisha)
    // And a void cannot be corrected.
    refused { repo.edit("a", same(amount = 5)) }
  }

  @Test
  fun voidingARejectedEntryJustDropsItOnThePhone() = runTest {
    dao.insert(pendingRow("f").copy(syncState = SyncState.FAILED))
    repo.void("f", "x")
    assertEquals(SyncState.DISCARDED, dao.rows.getValue("f").syncState)
    assertTrue(changes.changes.isEmpty())
    assertEquals(0, dao.unsyncedCount())
  }

  @Test
  fun membersChangeOnlyTheirOwnEntriesAndViewersNone() = runTest {
    synced("other", createdBy = "u-abba")
    refused { repo.edit("other", same(amount = 1)) }
    refused { repo.void("other", "x") }
    settings.profile.value = PROFILE.copy(role = "admin")
    repo.void("other", "x")
    synced("mine")
    settings.profile.value = PROFILE.copy(role = "viewer")
    refused { repo.edit("mine", same(amount = 1)) }
  }

  @Test
  fun reapplyAfterAConflictUsesTheServersNewVersion() = runTest {
    synced(version = 3)
    repo.edit("a", same(amount = 15000))
    changes.markProblem("a", SyncState.CONFLICT, "changed", snapshot(version = 5, voided = false))
    refused { repo.edit("a", same(amount = 1)) }
    repo.reapply("a")
    val c = changes.changes.getValue("a")
    assertEquals(SyncState.PENDING, c.state)
    assertEquals(5, c.baseVersion)
    assertEquals(15000L, c.amountPoisha)
    assertNull(c.serverSnapshot)
  }

  @Test
  fun aVoidElsewhereWinsOverACorrection() = runTest {
    synced()
    repo.edit("a", same(amount = 15000))
    changes.markProblem("a", SyncState.CONFLICT, "voided", snapshot(version = 4, voided = true))
    refused { repo.reapply("a") }
    repo.discardChange("a")
    assertTrue(changes.changes.isEmpty())
  }

  @Test
  fun aConflictingNewEntryCanBeSentAgainAsANewOne() = runTest {
    dao.insert(pendingRow("c").copy(syncState = SyncState.CONFLICT, lastError = "client_id reused"))
    repo.resendAsNew("c")
    assertEquals(SyncState.DISCARDED, dao.rows.getValue("c").syncState)
    val copy = dao.rows.getValue("new-id")
    assertEquals(SyncState.PENDING, copy.syncState)
    assertEquals(12050L, copy.amountPoisha)
    assertNull(copy.serverId)
    assertEquals(1, dao.unsyncedCount())
  }

  @Test
  fun waitingChangesBlockLogoutLikeNewEntries() = runTest {
    synced()
    assertEquals(0, dao.unsyncedCount())
    repo.void("a", "x")
    assertEquals(1, dao.unsyncedCount())
  }

  @Test
  fun overlayShowsTheChangeWithoutTouchingOtherFields() {
    val row = pendingRow("a").copy(note = "old")
    val shown =
      row.withChange(
        ChangeEntity(clientId = "a", householdId = "hh", createdByUserId = "u", kind = "edit", baseVersion = 1, accountId = "acc-bkash", noteChanged = true, note = null, createdLocallyAtMs = 0)
      )
    assertEquals("acc-bkash", shown.accountId)
    assertNull(shown.note)
    assertEquals(row.amountPoisha, shown.amountPoisha)
    assertEquals(row.categoryId, shown.categoryId)
    assertTrue(row.withChange(ChangeEntity(clientId = "a", householdId = "hh", createdByUserId = "u", kind = "void", baseVersion = 1, createdLocallyAtMs = 0)).voided)
  }

  private fun snapshot(version: Int, voided: Boolean) =
    json.encodeToString(
      TransactionDto.serializer(),
      TransactionDto(
        "srv-a", "a", "expense", 13000, "cat-groc", "acc-cash", null, "2026-10-15T18:30:00+06:00", null, PROFILE.userId,
        version, if (voided) "2026-10-16T09:00:00+06:00" else null,
      ),
    )
}
