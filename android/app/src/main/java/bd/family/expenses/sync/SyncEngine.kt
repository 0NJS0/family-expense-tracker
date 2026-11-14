package bd.family.expenses.sync

import bd.family.expenses.data.local.AccountEntity
import bd.family.expenses.data.local.ChangeDao
import bd.family.expenses.data.local.ChangeEntity
import bd.family.expenses.data.local.CategoryEntity
import bd.family.expenses.data.local.MemberEntity
import bd.family.expenses.data.local.ReferenceDao
import bd.family.expenses.data.local.SyncState
import bd.family.expenses.data.local.TransactionDao
import bd.family.expenses.data.local.TransactionEntity
import bd.family.expenses.data.remote.ApiException
import bd.family.expenses.data.remote.LoginRequiredException
import bd.family.expenses.data.remote.NetworkException
import bd.family.expenses.data.remote.SyncApi
import bd.family.expenses.data.remote.TransactionCreateDto
import bd.family.expenses.data.remote.TransactionDto
import bd.family.expenses.data.remote.json
import bd.family.expenses.data.settings.SettingsStore
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

sealed interface SyncOutcome {
  data class Done(val uploaded: Int, val rejected: Int) : SyncOutcome

  /** Temporary problem (offline, server error). Nothing was lost; try again later. */
  data class Retry(val uploaded: Int) : SyncOutcome

  /** The session ended. Entries stay on the phone until someone logs in. */
  data object LoginRequired : SyncOutcome

  /** Nobody is signed in. */
  data object NoSession : SyncOutcome
}

/**
 * Uploads pending entries, then waiting corrections and voids, then refreshes
 * reference data, recent history and the month summary. See
 * docs/offline-sync.md for the retry and conflict policy.
 */
class SyncEngine(
  private val transactions: TransactionDao,
  private val changes: ChangeDao,
  private val reference: ReferenceDao,
  private val api: SyncApi,
  private val settings: SettingsStore,
  private val now: () -> Instant = Instant::now,
  private val notifier: bd.family.expenses.notify.SyncNotifier = bd.family.expenses.notify.SyncNotifier.None,
  private val onPrefs: (bd.family.expenses.data.remote.NotificationPrefsDto, String) -> Unit = { _, _ -> },
  private val receipts: bd.family.expenses.data.local.ReceiptDao? = null,
  private val readFile: (String) -> ByteArray = { java.io.File(it).readBytes() },
  private val deleteFile: (String) -> Unit = { java.io.File(it).delete() },
) {
  suspend fun run(): SyncOutcome {
    val profile = settings.profile.value ?: return SyncOutcome.NoSession
    var uploaded = 0
    var rejected = 0
    try {
      // New entries first, so a correction of an entry made offline finds it on the server.
      var pass = 0
      while (pass++ < 2) {
        while (true) {
          val batch = transactions.pendingUploads(profile.householdId, profile.userId, BATCH)
          if (batch.isEmpty()) break
          for (row in batch) {
            when (upload(row)) {
              UploadResult.Ok -> uploaded++
              UploadResult.Rejected -> rejected++
              UploadResult.Temporary -> return SyncOutcome.Retry(uploaded)
              UploadResult.Folded -> {} // only changes are folded
            }
          }
        }
        var folded = false
        for (change in changes.pending(profile.householdId, profile.userId)) {
          when (sendChange(change, profile.householdId)) {
            UploadResult.Ok -> uploaded++
            UploadResult.Rejected -> rejected++
            UploadResult.Temporary -> return SyncOutcome.Retry(uploaded)
            // A change to a rejected entry was folded into it: upload that entry again.
            UploadResult.Folded -> folded = true
            null -> {}
          }
        }
        if (!folded) break
      }
      // Receipts after their entries, so each has a server id to attach to.
      receipts?.let { dao ->
        for (item in dao.pending(profile.householdId, profile.userId)) {
          when (sendReceipt(dao, item)) {
            UploadResult.Ok -> uploaded++
            UploadResult.Rejected -> rejected++
            UploadResult.Temporary -> return SyncOutcome.Retry(uploaded)
            UploadResult.Folded, null -> {}
          }
        }
      }
      refreshFromServer(profile.householdId, profile.timezone)
      settings.lastSyncAtMs = now().toEpochMilli()
      return SyncOutcome.Done(uploaded, rejected)
    } catch (e: LoginRequiredException) {
      return SyncOutcome.LoginRequired
    } catch (e: NetworkException) {
      return SyncOutcome.Retry(uploaded)
    } catch (e: ApiException) {
      // A failed refresh of reference data or summary: uploads are already safe.
      return SyncOutcome.Retry(uploaded)
    }
  }

  private enum class UploadResult { Ok, Rejected, Temporary, Folded }

  /** Upload one receipt photo; the local copy is deleted once the server has it. */
  private suspend fun sendReceipt(dao: bd.family.expenses.data.local.ReceiptDao, item: bd.family.expenses.data.local.ReceiptUploadEntity): UploadResult? {
    val row = transactions.get(item.clientId) ?: return null
    if (row.syncState != SyncState.SYNCED) return null // its entry is not on the server yet
    val serverId = row.serverId ?: return null
    val bytes =
      try {
        readFile(item.filePath)
      } catch (e: java.io.IOException) {
        dao.mark(item.id, SyncState.FAILED, "photo file missing")
        return UploadResult.Rejected
      }
    return try {
      api.uploadReceipt(serverId, bytes)
      dao.mark(item.id, SyncState.SYNCED, null)
      deleteFile(item.filePath)
      UploadResult.Ok
    } catch (e: NetworkException) {
      dao.recordAttempt(item.id, "network")
      UploadResult.Temporary
    } catch (e: ApiException) {
      if (e.status in PERMANENT || e.status == 409) {
        dao.mark(item.id, SyncState.FAILED, e.message) // the photo stays on the phone
        UploadResult.Rejected
      } else {
        dao.recordAttempt(item.id, "server ${e.status}")
        UploadResult.Temporary
      }
    }
  }

  /**
   * Sends one correction or void with the version the person saw. A 409 is
   * checked against the server's current copy: if it already shows the change
   * (our earlier attempt arrived but the reply was lost) it is done; otherwise
   * it becomes a CONFLICT for the person to decide. Nothing is overwritten.
   */
  private suspend fun sendChange(change: ChangeEntity, householdId: String): UploadResult? {
    val row = transactions.get(change.clientId)
    if (row == null) {
      changes.markProblem(change.clientId, SyncState.FAILED, "entry not found on this phone", null)
      return UploadResult.Rejected
    }
    when (row.syncState) {
      // Its creation goes first; the change follows in a later pass.
      SyncState.PENDING, SyncState.CONFLICT, SyncState.DISCARDED -> return null
      SyncState.FAILED -> {
        // The server never stored this entry, so the change is applied to it locally.
        changes.foldIntoRejected(row, change)
        return UploadResult.Folded
      }
      SyncState.SYNCED -> {}
    }
    val serverId = row.serverId ?: return null
    return try {
      val result =
        if (change.isVoid) api.voidTransaction(serverId, change.baseVersion, change.voidReason ?: "")
        else api.updateTransaction(serverId, change.toPatchBody())
      changes.complete(change.clientId, result.toEntity(householdId).keepingLocal(row))
      UploadResult.Ok
    } catch (e: NetworkException) {
      changes.recordAttempt(change.clientId, "network")
      UploadResult.Temporary
    } catch (e: ApiException) {
      when {
        e.status == 409 -> {
          val current =
            try {
              api.transaction(serverId)
            } catch (e: NetworkException) {
              changes.recordAttempt(change.clientId, "network")
              return UploadResult.Temporary
            }
          val currentRow = current.toEntity(householdId).keepingLocal(row)
          if (change.alreadyAppliedTo(current)) {
            changes.complete(change.clientId, currentRow)
            UploadResult.Ok
          } else {
            changes.conflict(change.clientId, currentRow, e.message, json.encodeToString(TransactionDto.serializer(), current))
            UploadResult.Rejected
          }
        }
        e.status in PERMANENT -> {
          changes.markProblem(change.clientId, SyncState.FAILED, e.message, null)
          UploadResult.Rejected
        }
        else -> {
          changes.recordAttempt(change.clientId, "server ${e.status}")
          UploadResult.Temporary
        }
      }
    }
  }

  private suspend fun upload(row: TransactionEntity): UploadResult =
    try {
      val saved = api.createTransaction(row.toCreateDto())
      transactions.markSynced(row.clientId, saved.id, saved.version)
      UploadResult.Ok
    } catch (e: NetworkException) {
      // The request may or may not have reached the server. Retrying with the
      // same client id is safe: the server returns the existing transaction.
      transactions.recordAttempt(row.clientId, "network")
      UploadResult.Temporary
    } catch (e: ApiException) {
      when {
        e.status == 409 -> {
          transactions.markProblem(row.clientId, SyncState.CONFLICT, e.message)
          UploadResult.Rejected
        }
        e.status in PERMANENT -> {
          transactions.markProblem(row.clientId, SyncState.FAILED, e.message)
          UploadResult.Rejected
        }
        else -> {
          transactions.recordAttempt(row.clientId, "server ${e.status}")
          UploadResult.Temporary
        }
      }
    }

  private suspend fun refreshFromServer(householdId: String, timezone: String) {
    // Follow role changes made on the dashboard (e.g. promoted to admin).
    val me = api.me()
    val membership = me.memberships.firstOrNull { it.householdId == householdId }
    settings.profile.value?.let { current ->
      if (membership != null && membership.role != current.role) {
        settings.saveProfile(current.copy(role = membership.role))
      }
    }

    val knownAccounts = reference.accountIds(householdId).toSet()
    val accounts = api.accounts().map { AccountEntity(it.id, householdId, it.name, it.kind, it.isArchived) }
    val categories = api.categories().map { CategoryEntity(it.id, householdId, it.kind, it.nameEn, it.nameBn, it.isArchived) }
    val members = api.members().map { MemberEntity(householdId, it.userId, it.displayName, it.isActive) }
    reference.replaceAll(householdId, accounts, categories, members)
    // Accounts this person can no longer see (made private): drop their server copies.
    val visible = accounts.map { it.id }
    transactions.removeSyncedOutside(householdId, visible)
    // An account that became visible brings older history: pull the feed from the start.
    if (knownAccounts.isNotEmpty() && !knownAccounts.containsAll(visible)) settings.syncCursor = null

    // Every change since the last sync, including edits and voids made elsewhere.
    // The cursor is saved after each merged page, so an interrupted pull resumes.
    var cursor = settings.syncCursor
    var pages = 0
    do {
      val page = api.changes(cursor, PAGE)
      transactions.mergeFromServer(page.items.map { it.toEntity(householdId) })
      if (page.removed.isNotEmpty()) transactions.removeSynced(page.removed)
      cursor = page.nextCursor
      settings.syncCursor = cursor
    } while (page.hasMore && ++pages < MAX_PAGES)

    val today = LocalDate.now(ZoneId.of(timezone))
    settings.cachedSummary = api.summary(today.withDayOfMonth(1).toString(), today.toString())
    settings.cachedTodaySummary = api.summary(today.toString(), today.toString())
    settings.cachedBalances = api.balances()
    settings.cachedBudgets =
      try {
        api.budgets()
      } catch (e: ApiException) {
        // A server without budgets (older version) is not a sync failure.
        if (e.status == 404) null else throw e
      }
    settings.cachedDue =
      try {
        api.dueOccurrences()
      } catch (e: ApiException) {
        if (e.status == 404) null else throw e
      }

    // Reminder choices follow the person; new due items and alerts are shown once.
    val prefs =
      try {
        api.notificationPrefs()
      } catch (e: ApiException) {
        if (e.status == 404) settings.notificationPrefs else throw e
      }
    settings.notificationPrefs = prefs
    onPrefs(prefs, timezone)
    val isAdmin = settings.profile.value?.isAdmin == true
    val alerts =
      if (isAdmin) {
        try {
          api.budgetAlerts(today.withDayOfMonth(1).toString().substring(0, 7))
        } catch (e: ApiException) {
          if (e.status == 404 || e.status == 403) emptyList() else throw e
        }
      } else {
        emptyList()
      }
    val plan = bd.family.expenses.notify.NotificationPlan.afterSync(prefs, isAdmin, settings.cachedDue.orEmpty(), alerts, settings.notifiedKeys)
    settings.notifiedKeys = plan.seen
    notifier.show(plan)
  }

  private companion object {
    const val BATCH = 20
    const val PAGE = 200
    // At most 10,000 changes per run; the rest follow on the next run.
    const val MAX_PAGES = 50
    // Client errors that retrying cannot fix. 408 and 429 are temporary.
    val PERMANENT = setOf(400, 403, 404, 422)
  }
}

/** PATCH body: the version the person saw and only the fields that change. */
fun ChangeEntity.toPatchBody(): JsonObject = buildJsonObject {
  put("version", JsonPrimitive(baseVersion))
  amountPoisha?.let { put("amount_poisha", JsonPrimitive(it)) }
  categoryId?.let { put("category_id", JsonPrimitive(it)) }
  accountId?.let { put("account_id", JsonPrimitive(it)) }
  destinationAccountId?.let { put("destination_account_id", JsonPrimitive(it)) }
  occurredAt?.let { put("occurred_at", JsonPrimitive(it)) }
  if (noteChanged) put("note", note?.let { JsonPrimitive(it) } ?: JsonNull)
}

/** Does the server's copy already show this change? (A retry after a lost reply.) */
fun ChangeEntity.alreadyAppliedTo(server: TransactionDto): Boolean {
  if (isVoid) return server.voidedAt != null
  if (server.voidedAt != null) return false
  fun <T> same(mine: T?, theirs: T) = mine == null || mine == theirs
  return same(amountPoisha, server.amountPoisha) &&
    same(categoryId, server.categoryId) &&
    same(accountId, server.accountId) &&
    same(destinationAccountId, server.destinationAccountId) &&
    (occurredAt == null || OffsetDateTime.parse(occurredAt).isEqual(OffsetDateTime.parse(server.occurredAt))) &&
    (!noteChanged || note == server.note)
}

/** A server row replacing a local one keeps what only the phone knows. */
private fun TransactionEntity.keepingLocal(local: TransactionEntity) =
  copy(createdByUserId = createdByUserId ?: local.createdByUserId, createdLocallyAtMs = local.createdLocallyAtMs)

fun TransactionEntity.toCreateDto() =
  TransactionCreateDto(
    clientId = clientId,
    type = type,
    amountPoisha = amountPoisha,
    categoryId = categoryId,
    accountId = accountId,
    destinationAccountId = destinationAccountId,
    occurredAt = occurredAt,
    note = note,
    attributedUserId = attributedUserId,
  )

fun TransactionDto.toEntity(householdId: String) =
  TransactionEntity(
    clientId = clientId,
    householdId = householdId,
    serverId = id,
    type = type,
    amountPoisha = amountPoisha,
    categoryId = categoryId,
    accountId = accountId,
    destinationAccountId = destinationAccountId,
    occurredAt = occurredAt,
    occurredAtEpochMs = OffsetDateTime.parse(occurredAt).toInstant().toEpochMilli(),
    note = note,
    attributedUserId = attributedUserId,
    createdByUserId = createdByUserId,
    syncState = SyncState.SYNCED,
    serverVersion = version,
    voided = voidedAt != null,
    createdLocallyAtMs = 0,
  )
