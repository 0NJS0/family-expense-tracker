package bd.family.expenses.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Upload state of a locally known transaction (docs/offline-sync.md). */
enum class SyncState {
  /** Saved on the phone; not yet confirmed by the server. Retried automatically. */
  PENDING,

  /** The server has it. */
  SYNCED,

  /** The server rejected it (e.g. validation); needs a person to look at it. */
  FAILED,

  /** The server holds different data for the same client id; never overwritten silently. */
  CONFLICT,

  /**
   * A rejected (never stored) entry the person chose to drop, or replaced with a
   * new copy. Kept on the phone, hidden from lists, never uploaded.
   */
  DISCARDED,
}

/**
 * One transaction as the phone knows it. The client-generated [clientId] is
 * the primary key and the server's idempotency key, so the same row can be
 * uploaded any number of times without creating duplicates.
 */
@Entity(
  tableName = "transactions",
  indices = [Index("household_id", "sync_state"), Index("household_id", "occurred_at_epoch_ms")],
)
data class TransactionEntity(
  @PrimaryKey @ColumnInfo(name = "client_id") val clientId: String,
  @ColumnInfo(name = "household_id") val householdId: String,
  @ColumnInfo(name = "server_id") val serverId: String?,
  val type: String,
  @ColumnInfo(name = "amount_poisha") val amountPoisha: Long,
  @ColumnInfo(name = "category_id") val categoryId: String?,
  @ColumnInfo(name = "account_id") val accountId: String,
  @ColumnInfo(name = "destination_account_id") val destinationAccountId: String?,
  /** ISO-8601 instant with offset, exactly as sent to the server. */
  @ColumnInfo(name = "occurred_at") val occurredAt: String,
  @ColumnInfo(name = "occurred_at_epoch_ms") val occurredAtEpochMs: Long,
  val note: String?,
  @ColumnInfo(name = "attributed_user_id") val attributedUserId: String?,
  /** The user who entered it on this phone (null for rows pulled from the server). */
  @ColumnInfo(name = "created_by_user_id") val createdByUserId: String?,
  @ColumnInfo(name = "sync_state") val syncState: SyncState,
  val attempts: Int = 0,
  @ColumnInfo(name = "last_error") val lastError: String? = null,
  @ColumnInfo(name = "server_version") val serverVersion: Int? = null,
  val voided: Boolean = false,
  @ColumnInfo(name = "created_locally_at_ms") val createdLocallyAtMs: Long,
)

/**
 * A correction or void of a transaction, waiting to be sent (the outbox for
 * changes, docs/offline-sync.md). At most one per transaction: a second
 * correction is merged into it. The transaction row keeps the server's data; the
 * screens show this change on top of it until the server confirms it, and the
 * row is then replaced by the server's answer and this change deleted.
 */
@Entity(tableName = "transaction_changes", indices = [Index("client_id", unique = true), Index("household_id")])
data class ChangeEntity(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  @ColumnInfo(name = "client_id") val clientId: String,
  @ColumnInfo(name = "household_id") val householdId: String,
  @ColumnInfo(name = "created_by_user_id") val createdByUserId: String,
  /** "edit" or "void". */
  val kind: String,
  /** The server version the person saw when making the change (optimistic concurrency). */
  @ColumnInfo(name = "base_version") val baseVersion: Int,
  // Edit: only the fields that change; null means "unchanged".
  @ColumnInfo(name = "amount_poisha") val amountPoisha: Long? = null,
  @ColumnInfo(name = "category_id") val categoryId: String? = null,
  @ColumnInfo(name = "account_id") val accountId: String? = null,
  @ColumnInfo(name = "destination_account_id") val destinationAccountId: String? = null,
  @ColumnInfo(name = "occurred_at") val occurredAt: String? = null,
  @ColumnInfo(name = "occurred_at_epoch_ms") val occurredAtEpochMs: Long? = null,
  /** The new note when [noteChanged]; null then means "remove the note". */
  val note: String? = null,
  @ColumnInfo(name = "note_changed") val noteChanged: Boolean = false,
  @ColumnInfo(name = "void_reason") val voidReason: String? = null,
  /** PENDING, FAILED (rejected, with [lastError]) or CONFLICT (see [serverSnapshot]). */
  val state: SyncState = SyncState.PENDING,
  @ColumnInfo(name = "last_error") val lastError: String? = null,
  val attempts: Int = 0,
  /** On CONFLICT: the server's current copy (JSON), shown next to the person's change. */
  @ColumnInfo(name = "server_snapshot") val serverSnapshot: String? = null,
  @ColumnInfo(name = "created_locally_at_ms") val createdLocallyAtMs: Long,
) {
  val isVoid: Boolean
    get() = kind == KIND_VOID

  companion object {
    const val KIND_EDIT = "edit"
    const val KIND_VOID = "void"
  }
}

/** A transaction with its waiting change, if any. */
data class TransactionWithChange(
  @androidx.room.Embedded val tx: TransactionEntity,
  @androidx.room.Relation(parentColumn = "client_id", entityColumn = "client_id") val change: ChangeEntity?,
) {
  /** What the person should see: the change applied on top of the stored row. */
  val shown: TransactionEntity
    get() = tx.withChange(change)
}

/** Overlay a waiting change on a transaction (pure; unit-tested). */
fun TransactionEntity.withChange(change: ChangeEntity?): TransactionEntity =
  when {
    change == null -> this
    change.isVoid -> copy(voided = true)
    else ->
      copy(
        amountPoisha = change.amountPoisha ?: amountPoisha,
        categoryId = change.categoryId ?: categoryId,
        accountId = change.accountId ?: accountId,
        destinationAccountId = change.destinationAccountId ?: destinationAccountId,
        occurredAt = change.occurredAt ?: occurredAt,
        occurredAtEpochMs = change.occurredAtEpochMs ?: occurredAtEpochMs,
        note = if (change.noteChanged) change.note else note,
      )
  }

/**
 * A receipt photo waiting to be uploaded (the outbox for receipts). The file is
 * in the app's private storage; it is deleted once the server has it.
 */
@Entity(tableName = "receipt_uploads", indices = [Index("client_id"), Index("household_id", "state")])
data class ReceiptUploadEntity(
  @PrimaryKey val id: String,
  /** The transaction it belongs to (its client id). */
  @ColumnInfo(name = "client_id") val clientId: String,
  @ColumnInfo(name = "household_id") val householdId: String,
  @ColumnInfo(name = "created_by_user_id") val createdByUserId: String,
  @ColumnInfo(name = "file_path") val filePath: String,
  /** PENDING, SYNCED or FAILED (with [lastError]). */
  val state: SyncState = SyncState.PENDING,
  @ColumnInfo(name = "last_error") val lastError: String? = null,
  val attempts: Int = 0,
  @ColumnInfo(name = "created_locally_at_ms") val createdLocallyAtMs: Long,
)

@Entity(tableName = "accounts", indices = [Index("household_id")])
data class AccountEntity(
  @PrimaryKey val id: String,
  @ColumnInfo(name = "household_id") val householdId: String,
  val name: String,
  val kind: String,
  @ColumnInfo(name = "is_archived") val isArchived: Boolean,
)

@Entity(tableName = "categories", indices = [Index("household_id")])
data class CategoryEntity(
  @PrimaryKey val id: String,
  @ColumnInfo(name = "household_id") val householdId: String,
  val kind: String,
  @ColumnInfo(name = "name_en") val nameEn: String,
  @ColumnInfo(name = "name_bn") val nameBn: String,
  @ColumnInfo(name = "is_archived") val isArchived: Boolean,
)

@Entity(tableName = "members", primaryKeys = ["household_id", "user_id"])
data class MemberEntity(
  @ColumnInfo(name = "household_id") val householdId: String,
  @ColumnInfo(name = "user_id") val userId: String,
  @ColumnInfo(name = "display_name") val displayName: String,
  @ColumnInfo(name = "is_active") val isActive: Boolean,
)

/** Integer totals of entries still waiting to be sent (never added to server totals). */
data class PendingTotals(
  @ColumnInfo(name = "count") val count: Int,
  @ColumnInfo(name = "expense_poisha") val expensePoisha: Long,
  @ColumnInfo(name = "income_poisha") val incomePoisha: Long,
)
