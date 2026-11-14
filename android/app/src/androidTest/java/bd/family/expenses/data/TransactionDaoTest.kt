package bd.family.expenses.data

import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import bd.family.expenses.data.local.AppDatabase
import bd.family.expenses.data.local.SyncState
import bd.family.expenses.data.local.TransactionEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Runs the real Room SQL on a device; the JVM tests use a fake DAO. */
@RunWith(AndroidJUnit4::class)
class TransactionDaoTest {
  private lateinit var db: AppDatabase
  private val dao get() = db.transactions()

  @Before
  fun open() {
    db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()
  }

  @After fun close() = db.close()

  private fun row(id: String, user: String = "u1", state: SyncState = SyncState.PENDING, type: String = "expense", amount: Long = 100, created: Long = 1, hh: String = "hh") =
    TransactionEntity(
      clientId = id, householdId = hh, serverId = null, type = type, amountPoisha = amount, categoryId = "c",
      accountId = "a", destinationAccountId = null, occurredAt = "2026-10-15T12:00:00+06:00", occurredAtEpochMs = created,
      note = null, attributedUserId = null, createdByUserId = user, syncState = state, createdLocallyAtMs = created,
    )

  @Test
  fun pendingUploadsAreThisUsersPendingRowsOldestFirst() = runTest {
    dao.insert(row("b", created = 2))
    dao.insert(row("a", created = 1))
    dao.insert(row("other-user", user = "u2"))
    dao.insert(row("other-household", hh = "hh2"))
    dao.insert(row("done", state = SyncState.SYNCED))
    dao.insert(row("rejected", state = SyncState.FAILED))
    assertEquals(listOf("a", "b"), dao.pendingUploads("hh", "u1", 10).map { it.clientId })
    assertEquals(listOf("a"), dao.pendingUploads("hh", "u1", 1).map { it.clientId })
  }

  @Test(expected = SQLiteConstraintException::class)
  fun aClientIdCanNeverBeOverwrittenByInsert() = runTest {
    dao.insert(row("a"))
    dao.insert(row("a", amount = 999))
  }

  @Test
  fun stateTransitions() = runTest {
    dao.insert(row("a"))
    dao.recordAttempt("a", "network")
    dao.markSynced("a", "srv-1", 1)
    val synced = dao.findByClientIds(listOf("a")).single()
    assertEquals(SyncState.SYNCED, synced.syncState)
    assertEquals(1, synced.attempts)
    assertEquals(null, synced.lastError)
    dao.markProblem("a", SyncState.CONFLICT, "reused")
    assertEquals(SyncState.CONFLICT, dao.findByClientIds(listOf("a")).single().syncState)
  }

  @Test
  fun pendingTotalsAreExactIntegerSums() = runTest {
    dao.insert(row("e1", amount = 100_000_000_000))
    dao.insert(row("e2", amount = 1))
    dao.insert(row("i1", type = "income", amount = 5_000))
    dao.insert(row("sent", state = SyncState.SYNCED, amount = 777))
    val totals = dao.observePendingTotals("hh").first()
    assertEquals(3, totals.count)
    assertEquals(100_000_000_001, totals.expensePoisha)
    assertEquals(5_000, totals.incomePoisha)
    assertEquals(3, dao.observeUnsyncedCount().first())
  }

  @Test
  fun mergeMarksUploadedRowsSyncedButNeverTouchesConflicts() = runTest {
    dao.insert(row("lost-reply", created = 5))
    dao.insert(row("conflict", state = SyncState.CONFLICT))
    val server =
      listOf(
        row("lost-reply", user = "ignored", state = SyncState.SYNCED, created = 0).copy(serverId = "srv-1", createdByUserId = null),
        row("conflict", state = SyncState.SYNCED).copy(serverId = "srv-2", amountPoisha = 42),
        row("new-from-web", state = SyncState.SYNCED).copy(serverId = "srv-3", createdByUserId = null),
      )
    dao.mergeFromServer(server)
    val byId = dao.findByClientIds(listOf("lost-reply", "conflict", "new-from-web")).associateBy { it.clientId }
    assertEquals(SyncState.SYNCED, byId.getValue("lost-reply").syncState)
    assertEquals("u1", byId.getValue("lost-reply").createdByUserId) // local authorship kept
    assertEquals(5L, byId.getValue("lost-reply").createdLocallyAtMs)
    assertEquals(SyncState.CONFLICT, byId.getValue("conflict").syncState)
    assertEquals(100L, byId.getValue("conflict").amountPoisha)
    assertTrue(byId.containsKey("new-from-web"))
  }

  @Test
  fun recentExcludesVoided() = runTest {
    dao.insert(row("visible", created = 2))
    dao.upsertAll(listOf(row("gone", state = SyncState.SYNCED, created = 3).copy(voided = true)))
    assertEquals(listOf("visible"), dao.observeRecent("hh", 10).first().map { it.clientId })
  }

  private fun change(id: String, kind: String = "edit", user: String = "u1", amount: Long? = 500) =
    bd.family.expenses.data.local.ChangeEntity(
      clientId = id, householdId = "hh", createdByUserId = user, kind = kind, baseVersion = 1,
      amountPoisha = if (kind == "edit") amount else null, voidReason = if (kind == "void") "x" else null, createdLocallyAtMs = 1,
    )

  @Test
  fun waitingChangesCountAsUnsentAndDiscardedRowsDoNot() = runTest {
    dao.insert(row("a", state = SyncState.SYNCED))
    dao.insert(row("d", state = SyncState.DISCARDED))
    assertEquals(0, dao.unsyncedCount())
    db.changes().save(change("a"))
    assertEquals(1, dao.unsyncedCount())
    assertEquals(1, dao.observePendingTotals("hh").first().count)
    // A waiting correction is counted, never added to the money totals.
    assertEquals(0L, dao.observePendingTotals("hh").first().expensePoisha)
  }

  @Test
  fun recentRowsCarryTheirWaitingChangeAndHideDiscarded() = runTest {
    dao.insert(row("a", state = SyncState.SYNCED, created = 2))
    dao.insert(row("b", state = SyncState.SYNCED, created = 1))
    dao.insert(row("d", state = SyncState.DISCARDED, created = 3))
    db.changes().save(change("a", amount = 777))
    val recent = dao.observeRecentWithChanges("hh", 10).first()
    assertEquals(listOf("a", "b"), recent.map { it.tx.clientId })
    assertEquals(777L, recent[0].shown.amountPoisha)
    assertEquals(100L, recent[0].tx.amountPoisha)
    assertEquals(null, recent[1].change)
  }

  @Test(expected = SQLiteConstraintException::class)
  fun atMostOneWaitingChangePerEntry() = runTest {
    db.changes().save(change("a"))
    db.changes().save(change("a"))
  }

  @Test
  fun completingAChangeStoresTheServerRowAndDropsTheChangeTogether() = runTest {
    dao.insert(row("a", state = SyncState.SYNCED))
    db.changes().save(change("a"))
    db.changes().complete("a", row("a", state = SyncState.SYNCED, amount = 500).copy(serverVersion = 2))
    assertEquals(500L, dao.get("a")?.amountPoisha)
    assertEquals(2, dao.get("a")?.serverVersion)
    assertEquals(null, db.changes().get("a"))
  }

  @Test
  fun changeToARejectedEntryIsFoldedIntoIt() = runTest {
    dao.insert(row("f", state = SyncState.FAILED))
    db.changes().foldIntoRejected(dao.get("f")!!, change("f", amount = 900))
    assertEquals(SyncState.PENDING, dao.get("f")?.syncState)
    assertEquals(900L, dao.get("f")?.amountPoisha)
    assertEquals(null, db.changes().get("f"))
  }

  @Test
  fun pendingChangesAreThisUsersOnlyAndNotProblemOnes() = runTest {
    db.changes().save(change("a"))
    db.changes().save(change("b", user = "u2"))
    db.changes().save(change("c"))
    db.changes().markProblem("c", SyncState.CONFLICT, "x", "{}")
    assertEquals(listOf("a"), db.changes().pending("hh", "u1").map { it.clientId })
  }

  @Test
  fun removingHiddenAccountsDropsOnlyServerCopiesWithoutChanges() = runTest {
    dao.insert(row("visible", state = SyncState.SYNCED))
    dao.insert(row("hidden", state = SyncState.SYNCED).copy(accountId = "secret"))
    dao.insert(row("hiddenDest", state = SyncState.SYNCED, type = "transfer").copy(categoryId = null, destinationAccountId = "secret"))
    dao.insert(row("unsent").copy(accountId = "secret"))
    dao.insert(row("failed", state = SyncState.FAILED).copy(accountId = "secret"))
    dao.insert(row("corrected", state = SyncState.SYNCED).copy(accountId = "secret"))
    db.changes().save(change("corrected"))
    dao.insert(row("otherHousehold", state = SyncState.SYNCED, hh = "hh2").copy(accountId = "secret"))

    dao.removeSyncedOutside("hh", listOf("a"))

    val left = dao.findByClientIds(listOf("visible", "hidden", "hiddenDest", "unsent", "failed", "corrected", "otherHousehold")).map { it.clientId }.toSet()
    assertEquals(setOf("visible", "unsent", "failed", "corrected", "otherHousehold"), left)
  }

  @Test
  fun removedFromTheFeedDropsOnlyServerCopiesWithoutChanges() = runTest {
    dao.insert(row("a", state = SyncState.SYNCED))
    dao.insert(row("b", state = SyncState.SYNCED))
    dao.insert(row("p"))
    db.changes().save(change("b"))
    dao.removeSynced(listOf("a", "b", "p"))
    assertEquals(setOf("b", "p"), dao.findByClientIds(listOf("a", "b", "p")).map { it.clientId }.toSet())
  }

  @Test
  fun waitingReceiptsCountAsUnsentUntilSent() = runTest {
    val receipts = db.receipts()
    receipts.insert(bd.family.expenses.data.local.ReceiptUploadEntity("r1", "a", "hh", "u1", "/x.jpg", createdLocallyAtMs = 1))
    assertEquals(1, dao.unsyncedCount())
    assertEquals(listOf("r1"), receipts.pending("hh", "u1").map { it.id })
    assertTrue(receipts.pending("hh", "someone-else").isEmpty())
    receipts.mark("r1", SyncState.SYNCED, null)
    assertEquals(0, dao.unsyncedCount())
  }
}
