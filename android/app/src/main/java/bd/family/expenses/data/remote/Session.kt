package bd.family.expenses.data.remote

import bd.family.expenses.data.auth.TokenStore
import bd.family.expenses.data.settings.Profile
import bd.family.expenses.data.settings.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer

/** What the sync engine needs from the server. Implemented by [Session]; faked in tests. */
interface SyncApi {
  /** Idempotent on clientId: a replay returns the existing transaction. */
  suspend fun createTransaction(request: TransactionCreateDto): TransactionDto

  suspend fun accounts(): List<AccountDto>

  suspend fun categories(): List<CategoryDto>

  suspend fun members(): List<MemberDto>

  /** The household's change feed after [cursor] (null: from the beginning). */
  suspend fun changes(cursor: String?, limit: Int): ChangesPage

  suspend fun summary(dateFrom: String, dateTo: String): SummaryDto

  /** A partial edit: [body] holds "version" and only the changed fields. */
  suspend fun updateTransaction(serverId: String, body: kotlinx.serialization.json.JsonObject): TransactionDto

  suspend fun voidTransaction(serverId: String, version: Int, reason: String): TransactionDto

  suspend fun transaction(serverId: String): TransactionDto

  /** Current balance of every active account, derived by the server. */
  suspend fun balances(): BalancesDto

  /** This month's budgets against spending. */
  suspend fun budgets(): BudgetStatusDto

  /** Recurring entries due now (the server generates them on this call). */
  suspend fun dueOccurrences(): List<OccurrenceDto>

  suspend fun notificationPrefs(): NotificationPrefsDto

  /** Attach a receipt file to a server transaction; the same bytes again return the same receipt. */
  suspend fun uploadReceipt(serverTransactionId: String, bytes: ByteArray): ReceiptDto

  /** Budget alerts of a month (admins only on the server). */
  suspend fun budgetAlerts(month: String): List<BudgetAlertDto>

  /** Who am I and in which households (for role changes). */
  suspend fun me(): MeDto
}

sealed interface LoginResult {
  data class Success(val profile: Profile) : LoginResult

  data object InvalidServerUrl : LoginResult

  data object WrongCredentials : LoginResult

  data object NoHousehold : LoginResult

  /** Unsent entries belong to someone else; they must sign in to send them first. */
  data object OtherUserHasUnsentEntries : LoginResult

  data object TooManyAttempts : LoginResult

  data object Unreachable : LoginResult

  data class ServerError(val message: String) : LoginResult
}

/**
 * Authentication against the backend. The access token lives in memory; the
 * rotating refresh token is persisted encrypted by [TokenStore]. On a 401, one
 * shared refresh runs and the call is retried once. A rejected refresh sets
 * [loginRequired]; local data is never deleted because of it.
 */
class Session(
  private val http: HttpApi,
  private val tokens: TokenStore,
  private val settings: SettingsStore,
  private val requireHttps: Boolean,
  private val deviceName: String,
) : SyncApi {
  @Volatile private var accessToken: String? = null
  private val refreshLock = Mutex()
  private val _loginRequired = MutableStateFlow(false)
  val loginRequired: StateFlow<Boolean> = _loginRequired.asStateFlow()

  suspend fun login(
    serverUrl: String,
    username: String,
    password: String,
    mayLoginAs: suspend (userId: String) -> Boolean,
  ): LoginResult {
    val base = normaliseServerUrl(serverUrl, requireHttps) ?: return LoginResult.InvalidServerUrl
    return try {
      val body = json.encodeToString(LoginRequest.serializer(), LoginRequest(username.trim().lowercase(), password, deviceName))
      val pair =
        when (val r = http.call(base, "POST", "/auth/login", TokenPair.serializer(), body)) {
          is HttpResult.Ok -> r.value
          HttpResult.Unauthorized -> return LoginResult.WrongCredentials
        }
      val me = (http.call(base, "GET", "/me", MeDto.serializer(), accessToken = pair.accessToken) as? HttpResult.Ok)?.value
        ?: return LoginResult.WrongCredentials
      // The first household; Settings can switch to another (switchHousehold).
      val membership = me.memberships.firstOrNull() ?: return LoginResult.NoHousehold
      if (!mayLoginAs(me.user.id)) return LoginResult.OtherUserHasUnsentEntries
      val household =
        (http.call(base, "GET", "/households/current", HouseholdDto.serializer(), accessToken = pair.accessToken, householdId = membership.householdId)
          as? HttpResult.Ok)?.value ?: return LoginResult.WrongCredentials
      val profile =
        Profile(
          serverUrl = base.toString().removeSuffix("/api/v1"),
          userId = me.user.id,
          displayName = me.user.displayName,
          householdId = household.id,
          householdName = household.name,
          timezone = household.timezone,
          role = membership.role,
        )
      tokens.save(pair.refreshToken)
      accessToken = pair.accessToken
      settings.saveProfile(profile)
      _loginRequired.value = false
      LoginResult.Success(profile)
    } catch (e: NetworkException) {
      LoginResult.Unreachable
    } catch (e: ApiException) {
      when (e.status) {
        401, 422 -> LoginResult.WrongCredentials
        429 -> LoginResult.TooManyAttempts
        else -> LoginResult.ServerError(e.message)
      }
    }
  }

  /** Revoke the session on the server (best effort) and forget it locally. */
  suspend fun logout() {
    val refresh = tokens.load()
    val base = baseUrl()
    accessToken = null
    tokens.clear()
    if (refresh != null && base != null) {
      runCatching {
        http.call<Unit>(base, "POST", "/auth/logout", null, json.encodeToString(RefreshRequest.serializer(), RefreshRequest(refresh)))
      }
    }
  }

  val hasStoredSession: Boolean
    get() = settings.profile.value != null && tokens.load() != null

  // --- SyncApi ---------------------------------------------------------------

  override suspend fun createTransaction(request: TransactionCreateDto): TransactionDto =
    authorized("POST", "/transactions", TransactionDto.serializer(), json.encodeToString(TransactionCreateDto.serializer(), request))

  override suspend fun accounts(): List<AccountDto> =
    authorized("GET", "/accounts", ListSerializer(AccountDto.serializer()), query = mapOf("include_archived" to "true"))

  override suspend fun categories(): List<CategoryDto> =
    authorized("GET", "/categories", ListSerializer(CategoryDto.serializer()), query = mapOf("include_archived" to "true"))

  override suspend fun me(): MeDto = authorized("GET", "/me", MeDto.serializer())

  override suspend fun members(): List<MemberDto> = authorized("GET", "/members", ListSerializer(MemberDto.serializer()))

  override suspend fun changes(cursor: String?, limit: Int): ChangesPage =
    authorized(
      "GET",
      "/sync/changes",
      ChangesPage.serializer(),
      query = buildMap {
        put("limit", "$limit")
        cursor?.let { put("cursor", it) }
      },
    )

  override suspend fun summary(dateFrom: String, dateTo: String): SummaryDto =
    authorized("GET", "/reports/summary", SummaryDto.serializer(), query = mapOf("date_from" to dateFrom, "date_to" to dateTo))

  override suspend fun updateTransaction(serverId: String, body: kotlinx.serialization.json.JsonObject): TransactionDto =
    authorized("PATCH", "/transactions/$serverId", TransactionDto.serializer(), body.toString())

  override suspend fun voidTransaction(serverId: String, version: Int, reason: String): TransactionDto =
    authorized(
      "POST",
      "/transactions/$serverId/void",
      TransactionDto.serializer(),
      kotlinx.serialization.json.buildJsonObject {
        put("version", kotlinx.serialization.json.JsonPrimitive(version))
        put("reason", kotlinx.serialization.json.JsonPrimitive(reason))
      }.toString(),
    )

  override suspend fun transaction(serverId: String): TransactionDto =
    authorized("GET", "/transactions/$serverId", TransactionDto.serializer())

  override suspend fun balances(): BalancesDto = authorized("GET", "/accounts/balances", BalancesDto.serializer())

  override suspend fun budgets(): BudgetStatusDto = authorized("GET", "/budgets", BudgetStatusDto.serializer())

  override suspend fun dueOccurrences(): List<OccurrenceDto> =
    authorized(
      "GET",
      "/recurring/occurrences",
      ListSerializer(OccurrenceDto.serializer()),
      query = mapOf("status" to "pending"),
    )

  override suspend fun uploadReceipt(serverTransactionId: String, bytes: ByteArray): ReceiptDto =
    authorized("POST", "/transactions/$serverTransactionId/receipts", ReceiptDto.serializer(), bytes = bytes)

  override suspend fun notificationPrefs(): NotificationPrefsDto =
    authorized("GET", "/me/notifications", NotificationPrefsDto.serializer())

  override suspend fun budgetAlerts(month: String): List<BudgetAlertDto> =
    authorized("GET", "/budgets/alerts", ListSerializer(BudgetAlertDto.serializer()), query = mapOf("month" to month))

  /** Save reminder choices (online). */
  suspend fun saveNotificationPrefs(prefs: NotificationPrefsDto): NotificationPrefsDto =
    authorized("PUT", "/me/notifications", NotificationPrefsDto.serializer(), json.encodeToString(NotificationPrefsDto.serializer(), prefs))

  /** "Paid" (with the expected amount) or "skip" for a due recurring entry. Online only. */
  suspend fun resolveOccurrence(id: String, paid: Boolean): OccurrenceDto =
    authorized(
      "POST",
      "/recurring/occurrences/$id/" + (if (paid) "confirm" else "skip"),
      OccurrenceDto.serializer(),
      body = if (paid) "{}" else null,
    )

  /**
   * Make [householdId] the active household. Refused while anything is unsent
   * ([unsentCount] > 0), so no entry can be left waiting under the wrong family.
   * Cached figures of the old household are cleared; the next sync fills them.
   */
  suspend fun switchHousehold(householdId: String, unsentCount: suspend () -> Int): Boolean {
    val current = settings.profile.value ?: throw LoginRequiredException()
    if (current.householdId == householdId) return true
    if (unsentCount() > 0) return false
    val membership = me().memberships.firstOrNull { it.householdId == householdId } ?: throw ApiException(404, "not_found", "Not a member")
    val household = authorized("GET", "/households/current", HouseholdDto.serializer(), householdOverride = householdId)
    settings.cachedSummary = null
    settings.cachedTodaySummary = null
    settings.cachedBalances = null
    settings.cachedBudgets = null
    settings.cachedDue = null
    settings.lastAccountId = null
    // A new household also restarts the change feed (saveProfile resets the cursor).
    settings.saveProfile(
      current.copy(householdId = household.id, householdName = household.name, timezone = household.timezone, role = membership.role)
    )
    return true
  }

  // --- internals ---------------------------------------------------------------

  private fun baseUrl() = settings.profile.value?.let { normaliseServerUrl(it.serverUrl, requireHttps) }

  /** Authenticated request for the admin screens (same refresh and error handling). */
  suspend fun <T> request(
    method: String,
    path: String,
    serializer: KSerializer<T>,
    body: String? = null,
    query: Map<String, String> = emptyMap(),
  ): T = authorized(method, path, serializer, body, query)

  private suspend fun <T> authorized(
    method: String,
    path: String,
    serializer: KSerializer<T>,
    body: String? = null,
    query: Map<String, String> = emptyMap(),
    householdOverride: String? = null,
    bytes: ByteArray? = null,
  ): T {
    val profile = settings.profile.value ?: throw LoginRequiredException()
    val base = normaliseServerUrl(profile.serverUrl, requireHttps) ?: throw LoginRequiredException()
    repeat(2) {
      val token = accessToken ?: refresh(staleToken = null)
      when (val r = http.call(base, method, path, serializer, body, token, householdOverride ?: profile.householdId, query, bytes)) {
        is HttpResult.Ok -> return r.value
        HttpResult.Unauthorized -> refresh(staleToken = token)
      }
    }
    throw LoginRequiredException()
  }

  /** Single-flight refresh: callers that saw the same stale token share one rotation. */
  private suspend fun refresh(staleToken: String?): String =
    refreshLock.withLock {
      accessToken?.let { if (it != staleToken) return it }
      val base = baseUrl() ?: throw LoginRequiredException()
      val refreshToken = tokens.load() ?: throw requireLogin()
      val body = json.encodeToString(RefreshRequest.serializer(), RefreshRequest(refreshToken))
      // A network failure propagates as NetworkException and keeps the token for later.
      when (val r = http.call(base, "POST", "/auth/refresh", TokenPair.serializer(), body)) {
        is HttpResult.Ok -> {
          tokens.save(r.value.refreshToken)
          accessToken = r.value.accessToken
          _loginRequired.value = false
          r.value.accessToken
        }
        HttpResult.Unauthorized -> throw requireLogin()
      }
    }

  private fun requireLogin(): LoginRequiredException {
    accessToken = null
    tokens.clear()
    _loginRequired.value = true
    return LoginRequiredException()
  }
}
