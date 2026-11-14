package bd.family.expenses.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface TransactionDao {
  /** A new local entry. Aborts (throws) rather than overwrite an existing client id. */
  @Insert(onConflict = OnConflictStrategy.ABORT)
  suspend fun insert(entity: TransactionEntity)

  @Query(
    "SELECT * FROM transactions WHERE household_id = :householdId AND voided = 0 AND sync_state != 'DISCARDED' " +
      "ORDER BY occurred_at_epoch_ms DESC, created_locally_at_ms DESC LIMIT :limit"
  )
  fun observeRecent(householdId: String, limit: Int): Flow<List<TransactionEntity>>

  /** Like [observeRecent], with any waiting correction or void attached. */
  @Transaction
  @Query(
    "SELECT * FROM transactions WHERE household_id = :householdId AND voided = 0 AND sync_state != 'DISCARDED' " +
      "ORDER BY occurred_at_epoch_ms DESC, created_locally_at_ms DESC LIMIT :limit"
  )
  fun observeRecentWithChanges(householdId: String, limit: Int): Flow<List<TransactionWithChange>>

  @Transaction
  @Query("SELECT * FROM transactions WHERE client_id = :clientId")
  fun observeOne(clientId: String): Flow<TransactionWithChange?>

  @Query("SELECT * FROM transactions WHERE client_id = :clientId")
  suspend fun get(clientId: String): TransactionEntity?

  /** Entries this person entered today: on this phone, or elsewhere and dated today. */
  @Query(
    "SELECT COUNT(*) FROM transactions WHERE household_id = :householdId AND created_by_user_id = :userId " +
      "AND sync_state != 'DISCARDED' AND ((created_locally_at_ms >= :fromMs AND created_locally_at_ms < :toMs) " +
      "OR (occurred_at_epoch_ms >= :fromMs AND occurred_at_epoch_ms < :toMs))"
  )
  suspend fun countEnteredBetween(householdId: String, userId: String, fromMs: Long, toMs: Long): Int

  /** Replace a row the phone owns (e.g. fixing a rejected entry). */
  @androidx.room.Update suspend fun update(entity: TransactionEntity)

  /** Retire [old] (kept, never uploaded) and queue [copy] as a new entry, together. */
  @Transaction
  suspend fun replaceWithCopy(old: TransactionEntity, copy: TransactionEntity) {
    update(old.copy(syncState = SyncState.DISCARDED))
    insert(copy)
  }

  /** Entries this user created on this phone that the server has not confirmed, oldest first. */
  @Query(
    "SELECT * FROM transactions WHERE household_id = :householdId AND created_by_user_id = :userId " +
      "AND sync_state = 'PENDING' ORDER BY created_locally_at_ms ASC LIMIT :limit"
  )
  suspend fun pendingUploads(householdId: String, userId: String, limit: Int): List<TransactionEntity>

  @Query(
    "UPDATE transactions SET sync_state = 'SYNCED', server_id = :serverId, " +
      "server_version = :version, last_error = NULL WHERE client_id = :clientId"
  )
  suspend fun markSynced(clientId: String, serverId: String, version: Int)

  @Query("UPDATE transactions SET sync_state = :state, last_error = :error WHERE client_id = :clientId")
  suspend fun markProblem(clientId: String, state: SyncState, error: String)

  @Query("UPDATE transactions SET attempts = attempts + 1, last_error = :error WHERE client_id = :clientId")
  suspend fun recordAttempt(clientId: String, error: String?)

  /** Anything the server does not (correctly) have yet, including waiting changes. Blocks logout. */
  @Query(
    "SELECT (SELECT COUNT(*) FROM transactions WHERE sync_state NOT IN ('SYNCED', 'DISCARDED')) + " +
      "(SELECT COUNT(*) FROM transaction_changes) + (SELECT COUNT(*) FROM receipt_uploads WHERE state != 'SYNCED')"
  )
  fun observeUnsyncedCount(): Flow<Int>

  @Query(
    "SELECT (SELECT COUNT(*) FROM transactions WHERE sync_state NOT IN ('SYNCED', 'DISCARDED')) + " +
      "(SELECT COUNT(*) FROM transaction_changes) + (SELECT COUNT(*) FROM receipt_uploads WHERE state != 'SYNCED')"
  )
  suspend fun unsyncedCount(): Int

  /** New entries not yet on the server, plus waiting corrections (counted, not summed). */
  @Query(
    "SELECT COUNT(*) + (SELECT COUNT(*) FROM transaction_changes WHERE household_id = :householdId) AS count, " +
      "COALESCE(SUM(CASE WHEN type = 'expense' THEN amount_poisha ELSE 0 END), 0) AS expense_poisha, " +
      "COALESCE(SUM(CASE WHEN type = 'income' THEN amount_poisha ELSE 0 END), 0) AS income_poisha " +
      "FROM transactions WHERE household_id = :householdId AND sync_state NOT IN ('SYNCED', 'DISCARDED')"
  )
  fun observePendingTotals(householdId: String): Flow<PendingTotals>

  @Query("SELECT * FROM transactions WHERE client_id IN (:clientIds)")
  suspend fun findByClientIds(clientIds: List<String>): List<TransactionEntity>

  @Upsert suspend fun upsertAll(entities: List<TransactionEntity>)

  /**
   * Merge the server's recent transactions. A server row with a client id we
   * hold locally means our upload succeeded (perhaps the response was lost),
   * so it simply becomes SYNCED. Local CONFLICT/FAILED rows are kept as they are.
   * A waiting correction is a separate row (transaction_changes) and is unaffected.
   */
  @Transaction
  suspend fun mergeFromServer(serverRows: List<TransactionEntity>) {
    if (serverRows.isEmpty()) return
    val local = findByClientIds(serverRows.map { it.clientId }).associateBy { it.clientId }
    val merged =
      serverRows.mapNotNull { remote ->
        val mine = local[remote.clientId]
        when {
          mine == null -> remote
          mine.syncState == SyncState.CONFLICT || mine.syncState == SyncState.FAILED -> null
          // The feed may repeat a row; never go back to an older version.
          (mine.serverVersion ?: 0) > (remote.serverVersion ?: 0) -> null
          else ->
            remote.copy(
              createdByUserId = remote.createdByUserId ?: mine.createdByUserId,
              createdLocallyAtMs = mine.createdLocallyAtMs,
            )
        }
      }
    upsertAll(merged)
  }

  /**
   * Drop the phone's copies of entries this person may no longer see (an
   * account became private). Only server copies: never an unsent entry, and
   * never one with a waiting correction.
   */
  @Query(
    "DELETE FROM transactions WHERE household_id = :householdId AND sync_state = 'SYNCED' " +
      "AND client_id NOT IN (SELECT client_id FROM transaction_changes) " +
      "AND (account_id NOT IN (:visibleAccountIds) OR " +
      "(destination_account_id IS NOT NULL AND destination_account_id NOT IN (:visibleAccountIds)))"
  )
  suspend fun removeSyncedOutside(householdId: String, visibleAccountIds: List<String>)

  /** The feed says these are no longer visible; same safety rules as [removeSyncedOutside]. */
  @Query(
    "DELETE FROM transactions WHERE client_id IN (:clientIds) AND sync_state = 'SYNCED' " +
      "AND client_id NOT IN (SELECT client_id FROM transaction_changes)"
  )
  suspend fun removeSynced(clientIds: List<String>)

  @Query("DELETE FROM transactions") suspend fun deleteAll()
}

/** The outbox for corrections and voids (see [ChangeEntity]). */
@Dao
interface ChangeDao {
  @Query("SELECT * FROM transaction_changes WHERE client_id = :clientId")
  suspend fun get(clientId: String): ChangeEntity?

  /** A new change; fails (rather than silently doing nothing) if the entry already has one. */
  @Insert(onConflict = OnConflictStrategy.ABORT)
  suspend fun insert(change: ChangeEntity)

  @androidx.room.Update suspend fun update(change: ChangeEntity)

  /** Insert a new change (id 0) or replace the existing one with the same id. */
  suspend fun save(change: ChangeEntity) = if (change.id == 0L) insert(change) else update(change)

  /** This user's changes ready to send, oldest first. */
  @Query(
    "SELECT * FROM transaction_changes WHERE household_id = :householdId AND created_by_user_id = :userId " +
      "AND state = 'PENDING' ORDER BY created_locally_at_ms ASC, id ASC"
  )
  suspend fun pending(householdId: String, userId: String): List<ChangeEntity>

  @Query("DELETE FROM transaction_changes WHERE client_id = :clientId") suspend fun delete(clientId: String)

  @Query(
    "UPDATE transaction_changes SET state = :state, last_error = :error, server_snapshot = :snapshot " +
      "WHERE client_id = :clientId"
  )
  suspend fun markProblem(clientId: String, state: SyncState, error: String, snapshot: String?)

  @Query("UPDATE transaction_changes SET attempts = attempts + 1, last_error = :error WHERE client_id = :clientId")
  suspend fun recordAttempt(clientId: String, error: String?)

  @Upsert suspend fun upsertTransaction(entity: TransactionEntity)

  /** The server confirmed the change: store its answer and drop the change, together. */
  @Transaction
  suspend fun complete(clientId: String, serverRow: TransactionEntity) {
    upsertTransaction(serverRow)
    delete(clientId)
  }

  /** Keep the server's current copy of the row next to a change that conflicted. */
  @Transaction
  suspend fun conflict(clientId: String, serverRow: TransactionEntity, message: String, snapshot: String) {
    upsertTransaction(serverRow)
    markProblem(clientId, SyncState.CONFLICT, message, snapshot)
  }

  /**
   * A change to an entry the server rejected (and so never stored) is applied
   * to the entry itself, which is then sent again; a void drops it instead.
   */
  @Transaction
  suspend fun foldIntoRejected(row: TransactionEntity, change: ChangeEntity) {
    check(row.syncState == SyncState.FAILED) { "only rejected entries can be changed locally" }
    upsertTransaction(
      if (change.isVoid) row.copy(syncState = SyncState.DISCARDED)
      else row.withChange(change).copy(syncState = SyncState.PENDING, lastError = null, attempts = 0)
    )
    delete(change.clientId)
  }

  @Query("DELETE FROM transaction_changes") suspend fun deleteAll()
}

@Dao
interface ReceiptDao {
  @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insert(item: ReceiptUploadEntity)

  @Query(
    "SELECT * FROM receipt_uploads WHERE household_id = :householdId AND created_by_user_id = :userId " +
      "AND state = 'PENDING' ORDER BY created_locally_at_ms"
  )
  suspend fun pending(householdId: String, userId: String): List<ReceiptUploadEntity>

  @Query("SELECT * FROM receipt_uploads WHERE client_id = :clientId ORDER BY created_locally_at_ms")
  fun observeFor(clientId: String): Flow<List<ReceiptUploadEntity>>

  @Query("UPDATE receipt_uploads SET state = :state, last_error = :error WHERE id = :id")
  suspend fun mark(id: String, state: SyncState, error: String?)

  @Query("UPDATE receipt_uploads SET attempts = attempts + 1, last_error = :error WHERE id = :id")
  suspend fun recordAttempt(id: String, error: String?)

  @Query("DELETE FROM receipt_uploads WHERE id = :id") suspend fun delete(id: String)

  @Query("SELECT COUNT(*) FROM receipt_uploads WHERE state != 'SYNCED'") suspend fun unsentCount(): Int

  @Query("SELECT COUNT(*) FROM receipt_uploads WHERE state != 'SYNCED'") fun observeUnsentCount(): Flow<Int>

  @Query("DELETE FROM receipt_uploads") suspend fun deleteAll()
}

@Dao
interface ReferenceDao {
  @Query("SELECT * FROM accounts WHERE household_id = :householdId ORDER BY name")
  fun observeAccounts(householdId: String): Flow<List<AccountEntity>>

  @Query("SELECT * FROM categories WHERE household_id = :householdId ORDER BY kind, name_en")
  fun observeCategories(householdId: String): Flow<List<CategoryEntity>>

  @Query("SELECT id FROM accounts WHERE household_id = :householdId")
  suspend fun accountIds(householdId: String): List<String>

  @Query("SELECT * FROM members WHERE household_id = :householdId ORDER BY display_name")
  fun observeMembers(householdId: String): Flow<List<MemberEntity>>

  @Query("DELETE FROM accounts WHERE household_id = :householdId") suspend fun clearAccounts(householdId: String)

  @Query("DELETE FROM categories WHERE household_id = :householdId") suspend fun clearCategories(householdId: String)

  @Query("DELETE FROM members WHERE household_id = :householdId") suspend fun clearMembers(householdId: String)

  @Insert suspend fun insertAccounts(items: List<AccountEntity>)

  @Insert suspend fun insertCategories(items: List<CategoryEntity>)

  @Insert suspend fun insertMembers(items: List<MemberEntity>)

  /** Replace the cached reference data atomically. */
  @Transaction
  suspend fun replaceAll(
    householdId: String,
    accounts: List<AccountEntity>,
    categories: List<CategoryEntity>,
    members: List<MemberEntity>,
  ) {
    clearAccounts(householdId)
    clearCategories(householdId)
    clearMembers(householdId)
    insertAccounts(accounts)
    insertCategories(categories)
    insertMembers(members)
  }

  @Query("DELETE FROM accounts") suspend fun deleteAllAccounts()

  @Query("DELETE FROM categories") suspend fun deleteAllCategories()

  @Query("DELETE FROM members") suspend fun deleteAllMembers()
}
