package bd.family.expenses.sync

import bd.family.expenses.data.local.SyncState
import bd.family.expenses.data.remote.ApiException
import bd.family.expenses.data.remote.LoginRequiredException
import bd.family.expenses.data.remote.NetworkException
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncEngineTest {
  private val dao = FakeTransactionDao()
  private val reference = FakeReferenceDao()
  private val server = FakeServer()
  private val settings = FakeSettings()
  private val changes = FakeChangeDao(dao)
  private val engine = SyncEngine(dao, changes, reference, server, settings, now = { Instant.ofEpochMilli(42) })

  private suspend fun state(id: String) = dao.rows.getValue(id).syncState

  @Test
  fun uploadsPendingEntriesThenRefreshesData() = runTest {
    dao.insert(pendingRow("a", createdAt = 1))
    dao.insert(pendingRow("b", createdAt = 2))
    assertEquals(SyncOutcome.Done(uploaded = 2, rejected = 0), engine.run())
    assertEquals(SyncState.SYNCED, state("a"))
    assertEquals("srv-1", dao.rows.getValue("a").serverId)
    assertEquals(listOf("Cash", "bKash"), reference.accounts.map { it.name })
    assertEquals(24100L, settings.cachedSummary?.expensePoisha)
    // Today's figures come from the server too, for the single day in the household timezone.
    val today = settings.cachedTodaySummary
    assertEquals(today?.dateFrom, today?.dateTo)
    assertEquals(42L, settings.lastSyncAtMs)
  }

  @Test
  fun offlineKeepsEntriesPendingAndAsksForRetry() = runTest {
    dao.insert(pendingRow("a"))
    server.failNextCreate = NetworkException(IOException("offline"))
    assertEquals(SyncOutcome.Retry(uploaded = 0), engine.run())
    assertEquals(SyncState.PENDING, state("a"))
    assertEquals(1, dao.rows.getValue("a").attempts)
    assertEquals(0, server.stored.size)
  }

  @Test
  fun lostResponseRetryDoesNotDuplicate() = runTest {
    dao.insert(pendingRow("a"))
    // The server stores the entry, but the phone never sees the reply.
    server.failNextCreate = NetworkException(IOException("timeout"))
    server.failAfterStoring = true
    assertEquals(SyncOutcome.Retry(uploaded = 0), engine.run())
    assertEquals(SyncState.PENDING, state("a"))
    // Reconnection: the same client id is sent again and the server replays it.
    assertEquals(SyncOutcome.Done(uploaded = 1, rejected = 0), engine.run())
    assertEquals(1, server.stored.size)
    assertEquals(2, server.createCalls)
    assertEquals(SyncState.SYNCED, state("a"))
  }

  @Test
  fun serverErrorStopsAndRetriesLater() = runTest {
    dao.insert(pendingRow("a", createdAt = 1))
    dao.insert(pendingRow("b", createdAt = 2))
    server.failNextCreate = ApiException(503, "unavailable", "maintenance")
    assertEquals(SyncOutcome.Retry(uploaded = 0), engine.run())
    assertEquals(SyncState.PENDING, state("a"))
    assertEquals(SyncState.PENDING, state("b"))
    assertEquals(1, server.createCalls) // stopped instead of hammering the server
  }

  @Test
  fun validationRejectionIsMarkedFailedAndOthersStillUpload() = runTest {
    dao.insert(pendingRow("bad", createdAt = 1))
    dao.insert(pendingRow("good", createdAt = 2))
    server.failNextCreate = ApiException(422, "validation_failed", "Category is archived")
    assertEquals(SyncOutcome.Done(uploaded = 1, rejected = 1), engine.run())
    assertEquals(SyncState.FAILED, state("bad"))
    assertEquals("Category is archived", dao.rows.getValue("bad").lastError)
    assertEquals(SyncState.SYNCED, state("good"))
    // A failed entry is not retried automatically; it needs a person.
    engine.run()
    assertEquals(SyncState.FAILED, state("bad"))
  }

  @Test
  fun conflictIsNeverOverwritten() = runTest {
    dao.insert(pendingRow("a"))
    server.failNextCreate = ApiException(409, "conflict", "client_id was already used for a different transaction")
    engine.run()
    assertEquals(SyncState.CONFLICT, state("a"))
    // Pulling recent server data must not silently replace the conflicting local row.
    assertNotNull(dao.rows.getValue("a").lastError)
  }

  @Test
  fun expiredSessionKeepsEverythingOnThePhone() = runTest {
    dao.insert(pendingRow("a"))
    server.rejectWith = null
    server.failNextCreate = LoginRequiredException()
    assertEquals(SyncOutcome.LoginRequired, engine.run())
    assertEquals(SyncState.PENDING, state("a"))
  }

  @Test
  fun onlyUploadsEntriesOfTheSignedInPerson() = runTest {
    dao.insert(pendingRow("mine"))
    dao.insert(pendingRow("theirs", createdBy = "u-someone-else"))
    engine.run()
    assertEquals(SyncState.SYNCED, state("mine"))
    assertEquals(SyncState.PENDING, state("theirs"))
    assertEquals(setOf("mine"), server.stored.keys)
  }

  @Test
  fun pullReconcilesAnEntryTheServerAlreadyHas() = runTest {
    // Entered by the previous person on this phone and uploaded, but the reply
    // was lost; it is not re-uploaded under the current person.
    val row = pendingRow("a", createdBy = "u-previous")
    server.createTransaction(row.toCreateDto())
    dao.insert(row)
    engine.run()
    assertEquals(SyncState.SYNCED, state("a"))
    assertEquals("srv-1", dao.rows.getValue("a").serverId)
    assertEquals(1, server.stored.size)
  }

  @Test
  fun pullFailureAfterUploadIsStillSafe() = runTest {
    dao.insert(pendingRow("a"))
    server.failPull = NetworkException(IOException("dropped"))
    assertEquals(SyncOutcome.Retry(uploaded = 1), engine.run())
    assertEquals(SyncState.SYNCED, state("a"))
    assertEquals(0L, settings.lastSyncAtMs)
  }

  @Test
  fun roleChangesOnTheServerReachThePhone() = runTest {
    assertEquals("member", settings.profile.value?.role)
    server.role = "admin"
    engine.run()
    assertEquals(true, settings.profile.value?.isAdmin)
    server.role = "viewer"
    engine.run()
    assertEquals("viewer", settings.profile.value?.role)
  }

  @Test
  fun nothingToDoWithoutASession() = runTest {
    settings.profile.value = null
    assertEquals(SyncOutcome.NoSession, engine.run())
    assertNull(settings.cachedSummary)
  }

  @Test
  fun largeBacklogIsUploadedInBatchesInOrder() = runTest {
    repeat(45) { dao.insert(pendingRow("t$it", amount = 100L + it, createdAt = it.toLong())) }
    assertEquals(SyncOutcome.Done(uploaded = 45, rejected = 0), engine.run())
    assertEquals((0 until 45).map { "t$it" }, server.stored.keys.toList())
  }

  @Test
  fun transferIsUploadedWithItsDestinationAndBalancesAreCached() = runTest {
    dao.insert(pendingRow("tr").copy(type = "transfer", categoryId = null, destinationAccountId = "acc-bkash"))
    assertEquals(SyncOutcome.Done(uploaded = 1, rejected = 0), engine.run())
    val sent = server.stored.getValue("tr").first
    assertEquals("transfer", sent.type)
    assertNull(sent.categoryId)
    assertEquals("acc-bkash", sent.destinationAccountId)
    assertEquals(SyncState.SYNCED, state("tr"))
    // Balances are the server's figures, stored as received.
    assertEquals(-12345L, settings.cachedBalances?.totalPoisha)
  }

  // --- corrections and voids (the change outbox) ---------------------------------

  private val repo =
    bd.family.expenses.data.ChangeRepository(dao, changes, settings, requestSync = {})

  private fun edit(amount: Long, note: String? = null) =
    bd.family.expenses.data.EntryEdit(amount, "cat-groc", "acc-cash", null, java.time.LocalDate.of(2026, 10, 15), note)

  /** An entry that the server has, as version 1. */
  private suspend fun sentEntry(id: String = "a"): String {
    dao.insert(pendingRow(id))
    engine.run()
    return dao.rows.getValue(id).serverId!!
  }

  @Test
  fun correctionIsSentWithTheVersionSeenAndOnlyTheChange() = runTest {
    sentEntry()
    repo.edit("a", edit(15000))
    assertEquals(SyncOutcome.Done(uploaded = 1, rejected = 0), engine.run())
    assertEquals("""{"version":1,"amount_poisha":15000}""", server.lastPatchBody.toString())
    val row = dao.rows.getValue("a")
    assertEquals(15000L, row.amountPoisha)
    assertEquals(2, row.serverVersion)
    assertTrue(changes.changes.isEmpty())
    assertEquals(0, dao.unsyncedCount())
  }

  @Test
  fun anEntryCorrectedBeforeItWasSentIsCreatedThenCorrectedInOneRun() = runTest {
    dao.insert(pendingRow("p"))
    repo.edit("p", edit(777, note = "fixed"))
    assertEquals(SyncOutcome.Done(uploaded = 2, rejected = 0), engine.run())
    // Created exactly as first saved, then corrected on top of version 1.
    assertEquals(12050L, server.stored.getValue("p").first.amountPoisha)
    assertEquals(777L, server.stored.getValue("p").second.amountPoisha)
    assertEquals("fixed", server.stored.getValue("p").second.note)
    assertEquals(777L, dao.rows.getValue("p").amountPoisha)
  }

  @Test
  fun lostReplyToACorrectionIsNotMistakenForAConflict() = runTest {
    sentEntry()
    repo.edit("a", edit(15000))
    server.loseNextChangeResponse = true
    assertEquals(SyncOutcome.Retry(uploaded = 0), engine.run())
    assertEquals(SyncState.PENDING, changes.changes.getValue("a").state)
    // The retry gets 409 (version moved on) but the server already shows the correction.
    assertEquals(SyncOutcome.Done(uploaded = 1, rejected = 0), engine.run())
    assertTrue(changes.changes.isEmpty())
    assertEquals(15000L, dao.rows.getValue("a").amountPoisha)
    assertEquals(2, server.stored.getValue("a").second.version)
  }

  @Test
  fun someoneElsesEditIsNeverOverwritten() = runTest {
    val serverId = sentEntry()
    repo.edit("a", edit(15000))
    server.editElsewhere(serverId, amount = 9900) // e.g. corrected on the dashboard meanwhile
    assertEquals(SyncOutcome.Done(uploaded = 0, rejected = 1), engine.run())
    assertEquals(9900L, server.stored.getValue("a").second.amountPoisha)
    val change = changes.changes.getValue("a")
    assertEquals(SyncState.CONFLICT, change.state)
    assertEquals(2, bd.family.expenses.data.ChangeRepository.serverCopy(change)?.version)
    // The phone now holds the server's copy, with the person's change kept beside it.
    assertEquals(9900L, dao.rows.getValue("a").amountPoisha)
    assertEquals(1, dao.unsyncedCount())

    // The person chooses to apply their correction again, on top of the new version.
    repo.reapply("a")
    assertEquals(SyncOutcome.Done(uploaded = 1, rejected = 0), engine.run())
    assertEquals(15000L, server.stored.getValue("a").second.amountPoisha)
    assertEquals(3, server.stored.getValue("a").second.version)
  }

  @Test
  fun voidIsSentAndTheEntryDisappearsFromTheLists() = runTest {
    sentEntry()
    repo.void("a", "Entered twice")
    assertEquals(SyncOutcome.Done(uploaded = 1, rejected = 0), engine.run())
    assertNotNull(server.stored.getValue("a").second.voidedAt)
    assertTrue(dao.rows.getValue("a").voided)
    assertTrue(changes.changes.isEmpty())
  }

  @Test
  fun correctionOfAnEntryVoidedElsewhereBecomesAConflict() = runTest {
    val serverId = sentEntry()
    repo.edit("a", edit(15000))
    server.voidTransaction(serverId, 1, "elsewhere")
    assertEquals(SyncOutcome.Done(uploaded = 0, rejected = 1), engine.run())
    val change = changes.changes.getValue("a")
    assertEquals(SyncState.CONFLICT, change.state)
    assertNotNull(bd.family.expenses.data.ChangeRepository.serverCopy(change)?.voidedAt)
    assertTrue(dao.rows.getValue("a").voided)
  }

  @Test
  fun rejectedCorrectionIsShownAndOthersStillGo() = runTest {
    sentEntry("a")
    sentEntry("b")
    repo.edit("a", edit(1))
    repo.edit("b", edit(2))
    server.rejectChangeWith = ApiException(422, "validation_error", "category is archived")
    assertEquals(SyncOutcome.Done(uploaded = 0, rejected = 2), engine.run())
    assertEquals(SyncState.FAILED, changes.changes.getValue("a").state)
    assertEquals("category is archived", changes.changes.getValue("a").lastError)
    // Failed changes are not retried automatically.
    server.rejectChangeWith = null
    val calls = server.changeCalls
    engine.run()
    assertEquals(calls, server.changeCalls)
  }

  @Test
  fun offlineCorrectionWaitsAndIsSentLater() = runTest {
    sentEntry()
    repo.edit("a", edit(15000))
    // No network for the change itself.
    val offline = object : bd.family.expenses.data.remote.SyncApi by server {
      override suspend fun updateTransaction(serverId: String, body: kotlinx.serialization.json.JsonObject) =
        throw NetworkException(IOException("offline"))
    }
    val offlineEngine = SyncEngine(dao, changes, reference, offline, settings)
    assertEquals(SyncOutcome.Retry(uploaded = 0), offlineEngine.run())
    assertEquals(SyncState.PENDING, changes.changes.getValue("a").state)
    assertEquals(1, changes.changes.getValue("a").attempts)
    assertEquals(SyncOutcome.Done(uploaded = 1, rejected = 0), engine.run())
  }

  @Test
  fun changeToARejectedEntryIsFoldedInAndTheEntryIsSentAgain() = runTest {
    dao.insert(pendingRow("f"))
    server.rejectWith = ApiException(422, "validation_error", "bad category")
    engine.run()
    assertEquals(SyncState.FAILED, state("f"))
    server.rejectWith = null
    // A change queued against it (e.g. from an older screen) is applied locally, then uploaded.
    changes.save(
      bd.family.expenses.data.local.ChangeEntity(clientId = "f", householdId = PROFILE.householdId, createdByUserId = PROFILE.userId, kind = "edit", baseVersion = 1, amountPoisha = 500, createdLocallyAtMs = 5)
    )
    assertEquals(SyncOutcome.Done(uploaded = 1, rejected = 0), engine.run())
    assertEquals(500L, server.stored.getValue("f").second.amountPoisha)
    assertEquals(SyncState.SYNCED, state("f"))
    assertTrue(changes.changes.isEmpty())
  }

  @Test
  fun onlyTheSignedInPersonsChangesAreSent() = runTest {
    sentEntry()
    changes.save(
      bd.family.expenses.data.local.ChangeEntity(clientId = "a", householdId = PROFILE.householdId, createdByUserId = "u-someone-else", kind = "void", baseVersion = 1, voidReason = "x", createdLocallyAtMs = 5)
    )
    engine.run()
    assertEquals(0, server.changeCalls)
  }

  // --- change feed ---------------------------------------------------------------

  @Test
  fun editsAndVoidsMadeElsewhereReachThePhoneThroughTheFeed() = runTest {
    val a = sentEntry("a")
    val b = sentEntry("b")
    server.editElsewhere(a, amount = 4400) // e.g. corrected on the dashboard
    server.voidTransaction(b, 1, "entered twice")
    engine.run()
    assertEquals(4400L, dao.rows.getValue("a").amountPoisha)
    assertEquals(2, dao.rows.getValue("a").serverVersion)
    assertTrue(dao.rows.getValue("b").voided)
  }

  @Test
  fun theFeedResumesFromTheSavedCursor() = runTest {
    sentEntry("a")
    val cursor = settings.syncCursor
    assertNotNull(cursor)
    engine.run()
    assertEquals(cursor, server.feedCursors.last())
  }

  @Test
  fun largeFeedsArePulledPageByPage() = runTest {
    repeat(450) { dao.insert(pendingRow("t$it", createdAt = it.toLong())) }
    engine.run()
    // 450 changes in pages of 200: three pages, each starting where the last ended.
    assertEquals(listOf(null, "200", "400"), server.feedCursors)
    assertEquals(450, dao.rows.values.count { it.syncState == SyncState.SYNCED })
  }

  @Test
  fun anOlderVersionFromTheFeedNeverReplacesANewerOne() = runTest {
    sentEntry("a")
    val newer = dao.rows.getValue("a").copy(amountPoisha = 1, serverVersion = 5)
    dao.upsertAll(listOf(newer))
    dao.mergeFromServer(listOf(newer.copy(amountPoisha = 2, serverVersion = 4)))
    assertEquals(1L, dao.rows.getValue("a").amountPoisha)
  }

  // --- private accounts ------------------------------------------------------------

  private fun bkashEdit() =
    bd.family.expenses.data.EntryEdit(1, "cat-groc", "acc-bkash", null, java.time.LocalDate.of(2026, 10, 15), null)

  @Test
  fun entriesOnAnAccountMadePrivateLeaveThisPhoneButUnsentOnesStay() = runTest {
    sentEntry("cash") // stays visible
    dao.insert(pendingRow("copy").copy(accountId = "acc-bkash"))
    dao.insert(pendingRow("corrected").copy(accountId = "acc-bkash"))
    engine.run()
    // A correction the server refused is still on the phone, waiting for the person.
    server.rejectChangeWith = ApiException(422, "validation_error", "no")
    repo.edit("corrected", bkashEdit())
    engine.run()
    server.rejectChangeWith = null

    // The admin makes bKash someone else's private account; a new bKash entry can no longer be sent.
    server.accountList = server.accountList.filter { it.id != "acc-bkash" }
    dao.insert(pendingRow("unsent", createdAt = 9).copy(accountId = "acc-bkash"))
    server.rejectWith = ApiException(404, "not_found", "Account not found")
    engine.run()

    assertTrue("cash" in dao.rows)
    assertTrue("copy" !in dao.rows) // a server copy on the hidden account: dropped
    assertTrue("corrected" in dao.rows) // has the person's correction: kept
    assertEquals(SyncState.FAILED, state("unsent")) // never sent: kept and shown as a problem
  }

  @Test
  fun theFeedsRemovedListDropsOnlyServerCopies() = runTest {
    sentEntry("a")
    sentEntry("b")
    server.rejectChangeWith = ApiException(422, "validation_error", "no")
    repo.edit("b", edit(5))
    server.removedInFeed = listOf("a", "b")
    engine.run()
    assertTrue("a" !in dao.rows)
    assertTrue("b" in dao.rows) // its correction is kept for the person to see
  }

  @Test
  fun anAccountThatBecomesVisibleRestartsTheFeed() = runTest {
    sentEntry("a")
    assertNotNull(settings.syncCursor)
    server.accountList = server.accountList + bd.family.expenses.data.remote.AccountDto("acc-new", "Shared now", "cash", false)
    server.feedCursors.clear()
    engine.run()
    assertEquals(null, server.feedCursors.first())
  }

  // --- budgets ----------------------------------------------------------------------

  @Test
  fun thisMonthsBudgetsAreCachedExactlyAsTheServerSentThem() = runTest {
    engine.run()
    val overall = settings.cachedBudgets?.items?.single()
    assertEquals(812_345L, overall?.spentPoisha)
    assertEquals(8123L, overall?.usedBasisPoints)
    assertEquals("warning", overall?.state)
  }

  @Test
  fun aServerWithoutBudgetsIsNotASyncFailure() = runTest {
    server.budgetStatus = null
    assertEquals(SyncOutcome.Done(uploaded = 0, rejected = 0), engine.run())
    assertNull(settings.cachedBudgets)
  }

  @Test
  fun basisPointsAreShownWithIntegersOnly() {
    assertEquals("81.2%", bd.family.expenses.ui.home.basisPointsText(8123, bangla = false))
    assertEquals("১০০.০%", bd.family.expenses.ui.home.basisPointsText(10_000, bangla = true))
    assertEquals("0.0%", bd.family.expenses.ui.home.basisPointsText(9, bangla = false))
  }

  @Test
  fun dueRecurringEntriesAreCachedAndAnOlderServerIsFine() = runTest {
    engine.run()
    assertEquals(listOf("Rent"), settings.cachedDue?.map { it.ruleName })
    server.due = null
    assertEquals(SyncOutcome.Done(uploaded = 0, rejected = 0), engine.run())
    assertNull(settings.cachedDue)
  }

  // --- notifications ------------------------------------------------------------------

  private val shown = mutableListOf<bd.family.expenses.notify.SyncNotifications>()
  private val scheduled = mutableListOf<bd.family.expenses.data.remote.NotificationPrefsDto>()
  private val notifyingEngine =
    SyncEngine(dao, changes, reference, server, settings, notifier = object : bd.family.expenses.notify.SyncNotifier {
      override fun show(items: bd.family.expenses.notify.SyncNotifications) { shown += items }
    }, onPrefs = { p, _ -> scheduled += p })

  @Test
  fun reminderChoicesFollowThePersonAndDueItemsAreNotifiedOnce() = runTest {
    server.prefs = bd.family.expenses.data.remote.NotificationPrefsDto(dailyReminder = true, reminderTime = "19:00:00")
    notifyingEngine.run()
    assertEquals("19:00:00", settings.notificationPrefs.reminderTime)
    assertEquals(true, scheduled.last().dailyReminder)
    assertEquals(listOf("occ-1"), shown.last().due.map { it.id })
    notifyingEngine.run()
    assertTrue(shown.last().due.isEmpty())
  }

  @Test
  fun adminsGetThisMonthsBudgetAlertsMembersAreNotAsked() = runTest {
    server.alerts = listOf(bd.family.expenses.data.remote.BudgetAlertDto(null, null, null, "2026-10-01", 80, 100, 85))
    notifyingEngine.run()
    assertTrue(server.alertMonths.isEmpty()) // a member
    assertTrue(shown.last().alerts.isEmpty())
    server.role = "admin"
    settings.profile.value = PROFILE.copy(role = "admin")
    notifyingEngine.run()
    assertEquals(1, shown.last().alerts.size)
    assertTrue(Regex("""\d{4}-\d{2}""").matches(server.alertMonths.last()))
  }

  // --- receipts ---------------------------------------------------------------------

  private val receiptDao = FakeReceiptDao()
  private val files = mutableMapOf<String, ByteArray>()
  private val receiptEngine =
    SyncEngine(dao, changes, reference, server, settings, receipts = receiptDao,
      readFile = { files[it] ?: throw java.io.IOException("missing") }, deleteFile = { files.remove(it) })

  private suspend fun photoFor(clientId: String, id: String = "p1"): String {
    val path = "/files/receipts/$id.jpg"
    files[path] = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 1, 2, 3)
    receiptDao.insert(bd.family.expenses.data.local.ReceiptUploadEntity(id, clientId, PROFILE.householdId, PROFILE.userId, path, createdLocallyAtMs = 1))
    return path
  }

  @Test
  fun aReceiptTakenOfflineIsSentRightAfterItsEntryThenTheLocalCopyIsDeleted() = runTest {
    dao.insert(pendingRow("a"))
    val path = photoFor("a")
    assertEquals(SyncOutcome.Done(uploaded = 2, rejected = 0), receiptEngine.run())
    val (serverId, bytes) = server.receiptsUploaded.single()
    assertEquals(dao.rows.getValue("a").serverId, serverId)
    assertEquals(listOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 1, 2, 3), bytes.toList())
    assertEquals(SyncState.SYNCED, receiptDao.items.getValue("p1").state)
    assertTrue(path !in files)
  }

  @Test
  fun aReceiptWaitsWhileItsEntryIsNotOnTheServer() = runTest {
    dao.insert(pendingRow("a").copy(syncState = SyncState.FAILED))
    photoFor("a")
    receiptEngine.run()
    assertTrue(server.receiptsUploaded.isEmpty())
    assertEquals(SyncState.PENDING, receiptDao.items.getValue("p1").state)
  }

  @Test
  fun aRefusedReceiptKeepsThePhotoAndOfflineRetriesLater() = runTest {
    sentEntry("a")
    val path = photoFor("a")
    server.rejectReceiptWith = NetworkException(IOException("offline"))
    assertEquals(SyncOutcome.Retry(uploaded = 0), receiptEngine.run())
    assertEquals(SyncState.PENDING, receiptDao.items.getValue("p1").state)
    server.rejectReceiptWith = ApiException(422, "validation_failed", "Only photos (JPEG, PNG, WebP) and PDF files are accepted")
    receiptEngine.run()
    assertEquals(SyncState.FAILED, receiptDao.items.getValue("p1").state)
    assertTrue(path in files) // the person's photo is never thrown away by a refusal
  }
}
