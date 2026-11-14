package bd.family.expenses.sync

import bd.family.expenses.data.local.AccountEntity
import bd.family.expenses.data.local.ChangeDao
import bd.family.expenses.data.local.ChangeEntity
import bd.family.expenses.data.local.TransactionWithChange
import bd.family.expenses.data.local.CategoryEntity
import bd.family.expenses.data.local.MemberEntity
import bd.family.expenses.data.local.PendingTotals
import bd.family.expenses.data.local.ReferenceDao
import bd.family.expenses.data.local.SyncState
import bd.family.expenses.data.local.TransactionDao
import bd.family.expenses.data.local.TransactionEntity
import bd.family.expenses.data.remote.AccountDto
import bd.family.expenses.data.remote.ApiException
import bd.family.expenses.data.remote.CategoryDto
import bd.family.expenses.data.remote.MemberDto
import bd.family.expenses.data.remote.NetworkException
import bd.family.expenses.data.remote.SummaryDto
import bd.family.expenses.data.remote.SyncApi
import bd.family.expenses.data.remote.TransactionCreateDto
import bd.family.expenses.data.remote.TransactionDto
import bd.family.expenses.data.settings.Profile
import bd.family.expenses.data.settings.SettingsStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf

val PROFILE = Profile("http://server", "u-amma", "Amma", "hh-1", "Home", "Asia/Dhaka")

class FakeSettings(profile: Profile? = PROFILE) : SettingsStore {
  override val profile = MutableStateFlow(profile)
  override val revision: StateFlow<Int> = MutableStateFlow(0)

  override fun saveProfile(profile: Profile) {
    val previous = this.profile.value
    // Like PrefsSettingsStore: another person or household restarts the change feed.
    if (previous == null || previous.userId != profile.userId || previous.householdId != profile.householdId) syncCursor = null
    this.profile.value = profile
  }

  override fun clearProfile() {
    profile.value = null
  }

  override var lastAccountId: String? = null
  override var lastSyncAtMs: Long = 0
  override var cachedSummary: SummaryDto? = null
  override var cachedTodaySummary: SummaryDto? = null
  override var cachedBalances: bd.family.expenses.data.remote.BalancesDto? = null
  override var syncCursor: String? = null
  override var cachedBudgets: bd.family.expenses.data.remote.BudgetStatusDto? = null
  override var cachedDue: List<bd.family.expenses.data.remote.OccurrenceDto>? = null
  override var notificationPrefs = bd.family.expenses.data.remote.NotificationPrefsDto()
  override var notifiedKeys: Set<String> = emptySet()
  override var lastReminderDate: String? = null
}

/** In-memory TransactionDao with the same semantics as the Room queries. */
class FakeTransactionDao : TransactionDao {
  val rows = linkedMapOf<String, TransactionEntity>()

  override suspend fun insert(entity: TransactionEntity) {
    check(entity.clientId !in rows) { "duplicate client id" }
    rows[entity.clientId] = entity
  }

  override fun observeRecent(householdId: String, limit: Int): Flow<List<TransactionEntity>> = flowOf(emptyList())

  override suspend fun pendingUploads(householdId: String, userId: String, limit: Int) =
    rows.values
      .filter { it.householdId == householdId && it.createdByUserId == userId && it.syncState == SyncState.PENDING }
      .sortedBy { it.createdLocallyAtMs }
      .take(limit)

  override suspend fun markSynced(clientId: String, serverId: String, version: Int) {
    rows.computeIfPresent(clientId) { _, r -> r.copy(syncState = SyncState.SYNCED, serverId = serverId, serverVersion = version, lastError = null) }
  }

  override suspend fun markProblem(clientId: String, state: SyncState, error: String) {
    rows.computeIfPresent(clientId) { _, r -> r.copy(syncState = state, lastError = error) }
  }

  override suspend fun recordAttempt(clientId: String, error: String?) {
    rows.computeIfPresent(clientId) { _, r -> r.copy(attempts = r.attempts + 1, lastError = error) }
  }

  /** Set by FakeChangeDao so counts include waiting changes, as in the Room query. */
  var changeCount: () -> Int = { 0 }
  var hasChange: (String) -> Boolean = { false }

  private fun removable(t: TransactionEntity) = t.syncState == SyncState.SYNCED && !hasChange(t.clientId)

  override suspend fun removeSyncedOutside(householdId: String, visibleAccountIds: List<String>) {
    rows.values.removeAll {
      it.householdId == householdId && removable(it) &&
        (it.accountId !in visibleAccountIds || (it.destinationAccountId != null && it.destinationAccountId !in visibleAccountIds))
    }
  }

  override suspend fun removeSynced(clientIds: List<String>) {
    rows.values.removeAll { it.clientId in clientIds && removable(it) }
  }

  override fun observeUnsyncedCount(): Flow<Int> = flowOf(unsyncedNow())

  override suspend fun unsyncedCount() = unsyncedNow()

  private fun unsyncedNow() =
    rows.values.count { it.syncState != SyncState.SYNCED && it.syncState != SyncState.DISCARDED } + changeCount()

  override fun observeRecentWithChanges(householdId: String, limit: Int) = flowOf(emptyList<TransactionWithChange>())

  override fun observeOne(clientId: String) = flowOf<TransactionWithChange?>(null)

  override suspend fun get(clientId: String) = rows[clientId]

  override suspend fun countEnteredBetween(householdId: String, userId: String, fromMs: Long, toMs: Long) =
    rows.values.count {
      it.householdId == householdId && it.createdByUserId == userId && it.syncState != SyncState.DISCARDED &&
        (it.createdLocallyAtMs in fromMs until toMs || it.occurredAtEpochMs in fromMs until toMs)
    }

  override suspend fun update(entity: TransactionEntity) {
    check(entity.clientId in rows)
    rows[entity.clientId] = entity
  }

  override fun observePendingTotals(householdId: String): Flow<PendingTotals> = flowOf(PendingTotals(0, 0, 0))

  override suspend fun findByClientIds(clientIds: List<String>) = clientIds.mapNotNull { rows[it] }

  override suspend fun upsertAll(entities: List<TransactionEntity>) {
    entities.forEach { rows[it.clientId] = it }
  }

  override suspend fun deleteAll() = rows.clear()
}

/** In-memory ChangeDao; its @Transaction defaults run against these methods. */
class FakeChangeDao(private val transactions: FakeTransactionDao) : ChangeDao {
  val changes = linkedMapOf<String, ChangeEntity>()
  private var nextId = 1L

  init {
    transactions.changeCount = { changes.size }
    transactions.hasChange = { it in changes }
  }

  override suspend fun get(clientId: String) = changes[clientId]

  override suspend fun insert(change: ChangeEntity) {
    // Mirrors the unique index on client_id.
    check(change.clientId !in changes) { "unique client_id" }
    changes[change.clientId] = change.copy(id = nextId++)
  }

  override suspend fun update(change: ChangeEntity) {
    check(changes[change.clientId]?.id == change.id) { "no such change" }
    changes[change.clientId] = change
  }

  override suspend fun pending(householdId: String, userId: String) =
    changes.values
      .filter { it.householdId == householdId && it.createdByUserId == userId && it.state == SyncState.PENDING }
      .sortedWith(compareBy({ it.createdLocallyAtMs }, { it.id }))

  override suspend fun delete(clientId: String) {
    changes.remove(clientId)
  }

  override suspend fun markProblem(clientId: String, state: SyncState, error: String, snapshot: String?) {
    changes.computeIfPresent(clientId) { _, c -> c.copy(state = state, lastError = error, serverSnapshot = snapshot) }
  }

  override suspend fun recordAttempt(clientId: String, error: String?) {
    changes.computeIfPresent(clientId) { _, c -> c.copy(attempts = c.attempts + 1, lastError = error) }
  }

  override suspend fun upsertTransaction(entity: TransactionEntity) {
    transactions.rows[entity.clientId] = entity
  }

  override suspend fun deleteAll() = changes.clear()
}

class FakeReceiptDao : bd.family.expenses.data.local.ReceiptDao {
  val items = linkedMapOf<String, bd.family.expenses.data.local.ReceiptUploadEntity>()

  override suspend fun insert(item: bd.family.expenses.data.local.ReceiptUploadEntity) {
    check(item.id !in items)
    items[item.id] = item
  }

  override suspend fun pending(householdId: String, userId: String) =
    items.values.filter { it.householdId == householdId && it.createdByUserId == userId && it.state == SyncState.PENDING }
      .sortedBy { it.createdLocallyAtMs }

  override fun observeFor(clientId: String) = flowOf(items.values.filter { it.clientId == clientId })

  override suspend fun mark(id: String, state: SyncState, error: String?) {
    items.computeIfPresent(id) { _, r -> r.copy(state = state, lastError = error) }
  }

  override suspend fun recordAttempt(id: String, error: String?) {
    items.computeIfPresent(id) { _, r -> r.copy(attempts = r.attempts + 1, lastError = error) }
  }

  override suspend fun delete(id: String) {
    items.remove(id)
  }

  override suspend fun unsentCount() = items.values.count { it.state != SyncState.SYNCED }

  override fun observeUnsentCount() = flowOf(items.values.count { it.state != SyncState.SYNCED })

  override suspend fun deleteAll() = items.clear()
}

class FakeReferenceDao : ReferenceDao {
  var accounts = emptyList<AccountEntity>()
  var categories = emptyList<CategoryEntity>()
  var members = emptyList<MemberEntity>()

  override fun observeAccounts(householdId: String) = flowOf(accounts)
  override fun observeCategories(householdId: String) = flowOf(categories)
  override fun observeMembers(householdId: String) = flowOf(members)
  override suspend fun accountIds(householdId: String) = accounts.filter { it.householdId == householdId }.map { it.id }
  override suspend fun clearAccounts(householdId: String) { accounts = emptyList() }
  override suspend fun clearCategories(householdId: String) { categories = emptyList() }
  override suspend fun clearMembers(householdId: String) { members = emptyList() }
  override suspend fun insertAccounts(items: List<AccountEntity>) { accounts = accounts + items }
  override suspend fun insertCategories(items: List<CategoryEntity>) { categories = categories + items }
  override suspend fun insertMembers(items: List<MemberEntity>) { members = members + items }
  override suspend fun deleteAllAccounts() { accounts = emptyList() }
  override suspend fun deleteAllCategories() { categories = emptyList() }
  override suspend fun deleteAllMembers() { members = emptyList() }
}

/**
 * A fake server with the real backend's idempotency: a repeated client id
 * returns the stored transaction; the same id with different data is a 409.
 */
class FakeServer : SyncApi {
  val stored = linkedMapOf<String, Pair<TransactionCreateDto, TransactionDto>>()
  var createCalls = 0
  /** Throw this (once) on the next create, before or after storing. */
  var failNextCreate: Exception? = null
  var failAfterStoring = false
  var rejectWith: ApiException? = null
  var failPull: Exception? = null

  override suspend fun createTransaction(request: TransactionCreateDto): TransactionDto {
    createCalls++
    rejectWith?.let { throw it }
    val failure = failNextCreate
    if (failure != null && !failAfterStoring) {
      failNextCreate = null
      throw failure
    }
    val existing = stored[request.clientId]
    val result =
      if (existing != null) {
        if (existing.first != request) throw ApiException(409, "conflict", "client_id reused")
        existing.second
      } else {
        TransactionDto(
          id = "srv-${stored.size + 1}", clientId = request.clientId, type = request.type,
          amountPoisha = request.amountPoisha, categoryId = request.categoryId, accountId = request.accountId,
          destinationAccountId = request.destinationAccountId, occurredAt = request.occurredAt, note = request.note,
          attributedUserId = request.attributedUserId ?: PROFILE.userId, version = 1, voidedAt = null,
          createdByUserId = PROFILE.userId,
        ).also {
          stored[request.clientId] = request to it
          touched(request.clientId)
        }
      }
    if (failure != null) {
      // The server saved it, but the response never reached the phone.
      failNextCreate = null
      throw failure
    }
    return result
  }

  var role = "member"

  override suspend fun me(): bd.family.expenses.data.remote.MeDto =
    pull {
      bd.family.expenses.data.remote.MeDto(
        bd.family.expenses.data.remote.UserDto(PROFILE.userId, "amma", "Amma", "bn"),
        listOf(bd.family.expenses.data.remote.MembershipDto(PROFILE.householdId, "Home", role)),
      )
    }

  var accountList = listOf(AccountDto("acc-cash", "Cash", "cash", false), AccountDto("acc-bkash", "bKash", "mobile_wallet", false))
  /** Client ids the feed reports as no longer visible. */
  var removedInFeed = listOf<String>()

  override suspend fun accounts(): List<AccountDto> = pull { accountList }
  override suspend fun categories(): List<CategoryDto> = pull { listOf(CategoryDto("cat-groc", "expense", "Groceries", "বাজার", false)) }
  override suspend fun members(): List<MemberDto> = pull { listOf(MemberDto("u-amma", "Amma", true)) }
  /** Like updated_at: every create or change gives the row a new, higher sequence number. */
  private val changedAt = linkedMapOf<String, Int>()
  private var seq = 0
  val feedCursors = mutableListOf<String?>()

  private fun touched(clientId: String) {
    changedAt[clientId] = ++seq
  }

  override suspend fun changes(cursor: String?, limit: Int): bd.family.expenses.data.remote.ChangesPage = pull {
    feedCursors += cursor
    val after = cursor?.toInt() ?: 0
    val newer = changedAt.entries.filter { it.value > after }.sortedBy { it.value }
    val page = newer.take(limit)
    val next = page.lastOrNull()?.value ?: after
    bd.family.expenses.data.remote.ChangesPage(page.map { stored.getValue(it.key).second }, removedInFeed, "$next", newer.size > limit)
  }
  override suspend fun summary(dateFrom: String, dateTo: String): SummaryDto =
    pull { SummaryDto(dateFrom, dateTo, true, 0, stored.values.sumOf { it.second.amountPoisha }, 0, stored.size) }

  var changeCalls = 0
  /** The next edit or void is applied, but its response is lost on the way back. */
  var loseNextChangeResponse = false
  var rejectChangeWith: ApiException? = null

  private fun byServerId(id: String) = stored.entries.first { it.value.second.id == id }

  /** Someone else (e.g. the dashboard) changes the entry: the version moves on. */
  fun editElsewhere(serverId: String, amount: Long) {
    val e = byServerId(serverId)
    e.setValue(e.value.first to e.value.second.let { it.copy(amountPoisha = amount, version = it.version + 1) })
    touched(e.key)
  }

  private fun change(serverId: String, version: Int, apply: (TransactionDto) -> TransactionDto): TransactionDto {
    changeCalls++
    rejectChangeWith?.let { throw it }
    val e = byServerId(serverId)
    val current = e.value.second
    if (current.voidedAt != null) throw ApiException(409, "conflict", "Transaction is voided and cannot be changed")
    if (current.version != version) throw ApiException(409, "conflict", "Transaction was changed by someone else")
    val next = apply(current).let { if (it == current) it else it.copy(version = current.version + 1) }
    e.setValue(e.value.first to next)
    if (next != current) touched(e.key)
    if (loseNextChangeResponse) {
      loseNextChangeResponse = false
      throw NetworkException(java.io.IOException("reply lost"))
    }
    return next
  }

  override suspend fun updateTransaction(serverId: String, body: kotlinx.serialization.json.JsonObject): TransactionDto {
    lastPatchBody = body
    val version = (body["version"] as kotlinx.serialization.json.JsonPrimitive).content.toInt()
    fun str(k: String) = (body[k] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
    return change(serverId, version) { t ->
      t.copy(
        amountPoisha = (body["amount_poisha"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLong() ?: t.amountPoisha,
        categoryId = str("category_id") ?: t.categoryId,
        accountId = str("account_id") ?: t.accountId,
        destinationAccountId = str("destination_account_id") ?: t.destinationAccountId,
        occurredAt = str("occurred_at") ?: t.occurredAt,
        note = if ("note" in body) str("note") else t.note,
      )
    }
  }

  var lastPatchBody: kotlinx.serialization.json.JsonObject? = null

  override suspend fun voidTransaction(serverId: String, version: Int, reason: String): TransactionDto =
    change(serverId, version) { it.copy(voidedAt = "2026-10-16T10:00:00+06:00") }

  override suspend fun transaction(serverId: String): TransactionDto = pull { byServerId(serverId).value.second }

  var budgetStatus: bd.family.expenses.data.remote.BudgetStatusDto? =
    bd.family.expenses.data.remote.BudgetStatusDto(
      "2026-10-01", true,
      listOf(bd.family.expenses.data.remote.BudgetItemDto(null, null, null, 1_000_000, 812_345, 187_655, 8123, "warning")),
    )

  var due: List<bd.family.expenses.data.remote.OccurrenceDto>? =
    listOf(bd.family.expenses.data.remote.OccurrenceDto("occ-1", "Rent", "expense", 1_500_000, "2026-10-01", "pending"))

  var prefs = bd.family.expenses.data.remote.NotificationPrefsDto()
  var alerts = listOf<bd.family.expenses.data.remote.BudgetAlertDto>()
  val alertMonths = mutableListOf<String>()

  override suspend fun notificationPrefs() = pull { prefs }

  /** Server transaction id → uploaded files. */
  val receiptsUploaded = mutableListOf<Pair<String, ByteArray>>()
  var rejectReceiptWith: Exception? = null

  override suspend fun uploadReceipt(serverTransactionId: String, bytes: ByteArray): bd.family.expenses.data.remote.ReceiptDto {
    rejectReceiptWith?.let { throw it }
    receiptsUploaded += serverTransactionId to bytes
    return bd.family.expenses.data.remote.ReceiptDto("r-${receiptsUploaded.size}", serverTransactionId, "image/jpeg", "pending")
  }

  override suspend fun budgetAlerts(month: String) = pull {
    alertMonths += month
    if (role != "admin") throw ApiException(403, "forbidden", "admins only")
    alerts
  }

  override suspend fun dueOccurrences() = pull { due ?: throw ApiException(404, "not_found", "Not Found") }

  override suspend fun budgets() = pull { budgetStatus ?: throw ApiException(404, "not_found", "Not Found") }

  override suspend fun balances(): bd.family.expenses.data.remote.BalancesDto =
    pull { bd.family.expenses.data.remote.BalancesDto(-12345, listOf(bd.family.expenses.data.remote.BalanceRowDto("acc-cash", "Cash", "cash", false, -12345))) }

  private fun <T> pull(block: () -> T): T {
    failPull?.let { throw it }
    return block()
  }
}

fun pendingRow(
  clientId: String,
  amount: Long = 12050,
  createdBy: String = PROFILE.userId,
  createdAt: Long = 1,
) =
  TransactionEntity(
    clientId = clientId, householdId = PROFILE.householdId, serverId = null, type = "expense",
    amountPoisha = amount, categoryId = "cat-groc", accountId = "acc-cash", destinationAccountId = null,
    occurredAt = "2026-10-15T18:30:00+06:00", occurredAtEpochMs = 1_792_067_400_000, note = null,
    attributedUserId = null, createdByUserId = createdBy, syncState = SyncState.PENDING,
    createdLocallyAtMs = createdAt,
  )
