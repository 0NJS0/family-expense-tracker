package bd.family.expenses.data

import bd.family.expenses.data.local.ChangeDao
import bd.family.expenses.data.local.ChangeEntity
import bd.family.expenses.data.local.SyncState
import bd.family.expenses.data.local.TransactionDao
import bd.family.expenses.data.local.TransactionEntity
import bd.family.expenses.data.remote.TransactionDto
import bd.family.expenses.data.remote.json
import bd.family.expenses.data.settings.Profile
import bd.family.expenses.data.settings.SettingsStore
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/** The values an entry should have after a correction (the whole form). */
data class EntryEdit(
  val amountPoisha: Long,
  val categoryId: String?,
  val accountId: String,
  val destinationAccountId: String?,
  val date: LocalDate,
  val note: String?,
)

/**
 * Corrections and voids made on the phone. Like new entries they are saved
 * first and sent later (docs/offline-sync.md): a change carries the server
 * version the person saw, so it can never silently overwrite someone else's.
 */
class ChangeRepository(
  private val transactions: TransactionDao,
  private val changes: ChangeDao,
  private val settings: SettingsStore,
  private val requestSync: () -> Unit,
  private val clock: Clock = Clock.systemUTC(),
  private val newId: () -> String = { UUID.randomUUID().toString() },
) {
  /** Saves a correction. Returns false when nothing differs from the entry. */
  suspend fun edit(clientId: String, edit: EntryEdit): Boolean {
    val profile = signedIn()
    val row = checkNotNull(transactions.get(clientId)) { "unknown entry" }
    val existing = changes.get(clientId)
    check(canChange(row, profile)) { "not allowed" }
    check(!row.voided && existing?.isVoid != true) { "voided entries cannot be corrected" }
    check(existing?.state != SyncState.CONFLICT) { "resolve the conflict first" }
    require((row.type == "transfer") == (edit.destinationAccountId != null && edit.categoryId == null)) { "wrong shape" }
    require(edit.destinationAccountId != edit.accountId) { "transfer to the same account" }

    val zone = ZoneId.of(profile.timezone)
    val note = edit.note?.trim()?.takeIf { it.isNotEmpty() }
    // The time only changes when the day does, so a correction keeps the original time.
    val rowDate = Instant.ofEpochMilli(row.occurredAtEpochMs).atZone(zone).toLocalDate()
    val occurred = if (edit.date == rowDate) null else occurrence(edit.date, zone, clock)

    when (row.syncState) {
      SyncState.FAILED -> {
        // The server rejected (never stored) it: correct it in place and send it again.
        transactions.update(
          row.copy(
            amountPoisha = edit.amountPoisha,
            categoryId = edit.categoryId,
            accountId = edit.accountId,
            destinationAccountId = edit.destinationAccountId,
            occurredAt = occurred?.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME) ?: row.occurredAt,
            occurredAtEpochMs = occurred?.toInstant()?.toEpochMilli() ?: row.occurredAtEpochMs,
            note = note,
            syncState = SyncState.PENDING,
            lastError = null,
            attempts = 0,
          )
        )
        changes.delete(clientId)
        requestSync()
        return true
      }
      SyncState.CONFLICT, SyncState.DISCARDED -> error("this entry cannot be corrected")
      SyncState.PENDING, SyncState.SYNCED -> {}
    }

    // Only fields that differ from what the server has are sent.
    val change =
      ChangeEntity(
        id = existing?.id ?: 0,
        clientId = clientId,
        householdId = row.householdId,
        createdByUserId = profile.userId,
        kind = ChangeEntity.KIND_EDIT,
        baseVersion = existing?.baseVersion ?: baseVersionOf(row),
        amountPoisha = edit.amountPoisha.takeIf { it != row.amountPoisha },
        categoryId = edit.categoryId?.takeIf { it != row.categoryId },
        accountId = edit.accountId.takeIf { it != row.accountId },
        destinationAccountId = edit.destinationAccountId?.takeIf { it != row.destinationAccountId },
        occurredAt = occurred?.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
        occurredAtEpochMs = occurred?.toInstant()?.toEpochMilli(),
        note = note,
        noteChanged = note != row.note,
        createdLocallyAtMs = existing?.createdLocallyAtMs ?: clock.millis(),
      )
    if (change.isNoOp()) {
      // Changed back to what the server has: nothing left to send.
      changes.delete(clientId)
      return false
    }
    changes.save(change)
    requestSync()
    return true
  }

  /** Voids (cancels) an entry. The server keeps it, marked void with the reason. */
  suspend fun void(clientId: String, reason: String) {
    val profile = signedIn()
    val row = checkNotNull(transactions.get(clientId)) { "unknown entry" }
    check(canChange(row, profile)) { "not allowed" }
    check(!row.voided) { "already voided" }
    val existing = changes.get(clientId)
    check(existing?.state != SyncState.CONFLICT) { "resolve the conflict first" }
    if (row.syncState == SyncState.FAILED) {
      // Never stored on the server: nothing to void there.
      discardRejected(clientId)
      return
    }
    check(row.syncState == SyncState.PENDING || row.syncState == SyncState.SYNCED) { "this entry cannot be voided" }
    changes.save(
      ChangeEntity(
        id = existing?.id ?: 0,
        clientId = clientId,
        householdId = row.householdId,
        createdByUserId = profile.userId,
        kind = ChangeEntity.KIND_VOID,
        baseVersion = existing?.baseVersion ?: baseVersionOf(row),
        voidReason = reason.trim().take(500).ifEmpty { "Voided on the phone" },
        createdLocallyAtMs = existing?.createdLocallyAtMs ?: clock.millis(),
      )
    )
    requestSync()
  }

  /** After a conflict: apply the person's change again, on top of the server's current version. */
  suspend fun reapply(clientId: String) {
    val change = checkNotNull(changes.get(clientId)) { "no change" }
    check(change.state == SyncState.CONFLICT) { "not in conflict" }
    val server = serverCopy(change) ?: error("no server copy")
    // A void made elsewhere wins (docs/offline-sync.md); there is nothing to apply to.
    check(server.voidedAt == null) { "voided on the server" }
    changes.save(change.copy(baseVersion = server.version, state = SyncState.PENDING, lastError = null, serverSnapshot = null, attempts = 0))
    requestSync()
  }

  /** Drop the person's waiting (or conflicting, or rejected) change; the server's copy stays. */
  suspend fun discardChange(clientId: String) = changes.delete(clientId)

  /** Drop a new entry the server rejected or never accepted. It is kept on the phone, hidden. */
  suspend fun discardRejected(clientId: String) {
    val row = checkNotNull(transactions.get(clientId)) { "unknown entry" }
    check(row.syncState == SyncState.FAILED || row.syncState == SyncState.CONFLICT) { "only rejected entries" }
    transactions.update(row.copy(syncState = SyncState.DISCARDED))
    changes.delete(clientId)
  }

  /**
   * The server already holds different data under this entry's id (CONFLICT on
   * create). Keep the person's version by sending it again as a new entry.
   */
  suspend fun resendAsNew(clientId: String) {
    val row = checkNotNull(transactions.get(clientId)) { "unknown entry" }
    check(row.syncState == SyncState.CONFLICT) { "only conflicting entries" }
    transactions.replaceWithCopy(
      row,
      row.copy(
        clientId = newId(),
        serverId = null,
        serverVersion = null,
        syncState = SyncState.PENDING,
        attempts = 0,
        lastError = null,
        createdLocallyAtMs = clock.millis(),
      ),
    )
    changes.delete(clientId)
    requestSync()
  }

  private fun signedIn(): Profile = checkNotNull(settings.profile.value) { "not signed in" }

  companion object {
    /** Members correct their own entries; admins any. Viewers none. The server checks again. */
    fun canChange(row: TransactionEntity, profile: Profile): Boolean =
      profile.isAdmin || (profile.role == "member" && row.createdByUserId == profile.userId)

    /** The server copy saved with a conflicting change. */
    fun serverCopy(change: ChangeEntity): TransactionDto? =
      change.serverSnapshot?.let { runCatching { json.decodeFromString(TransactionDto.serializer(), it) }.getOrNull() }

    // A new entry becomes version 1 when the server creates it.
    private fun baseVersionOf(row: TransactionEntity) = row.serverVersion ?: 1

    private fun ChangeEntity.isNoOp() =
      amountPoisha == null && categoryId == null && accountId == null && destinationAccountId == null &&
        occurredAt == null && !noteChanged
  }
}
