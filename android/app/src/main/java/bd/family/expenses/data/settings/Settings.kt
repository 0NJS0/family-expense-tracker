package bd.family.expenses.data.settings

import android.content.Context
import bd.family.expenses.data.remote.BalancesDto
import bd.family.expenses.data.remote.BudgetStatusDto
import bd.family.expenses.data.remote.SummaryDto
import bd.family.expenses.data.remote.json
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Who is signed in on this phone. Not secret (tokens live in TokenStore). */
data class Profile(
  val serverUrl: String,
  val userId: String,
  val displayName: String,
  val householdId: String,
  val householdName: String,
  val timezone: String,
  /** admin, member or viewer in this household; refreshed on every sync. */
  val role: String = "member",
) {
  val isAdmin: Boolean
    get() = role == "admin"
}

/** Small key-value settings. An interface so sync logic can be tested on the JVM. */
interface SettingsStore {
  val profile: StateFlow<Profile?>

  fun saveProfile(profile: Profile)

  fun clearProfile()

  var lastAccountId: String?
  var lastSyncAtMs: Long
  var cachedSummary: SummaryDto?

  /** Server totals for today only (household timezone). */
  var cachedTodaySummary: SummaryDto?

  /** Server-derived account balances from the last sync (never computed on the phone). */
  var cachedBalances: BalancesDto?

  /** This month's budgets from the last sync (server figures). */
  var cachedBudgets: BudgetStatusDto?

  /** Recurring entries due at the last sync. */
  var cachedDue: List<bd.family.expenses.data.remote.OccurrenceDto>?

  /** Reminder choices from the server (defaults until the first sync). */
  var notificationPrefs: bd.family.expenses.data.remote.NotificationPrefsDto

  /** Ids of due items and alerts already notified, so each is shown once. */
  var notifiedKeys: Set<String>

  /** Local date (household timezone) of the last reminder shown: at most one a day. */
  var lastReminderDate: String?

  /** Where this phone is in the household's change feed; reset when the person or household changes. */
  var syncCursor: String?

  /** Bumped whenever cached server data changes, so screens can re-read it. */
  val revision: StateFlow<Int>
}

class PrefsSettingsStore(context: Context) : SettingsStore {
  private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
  private val _profile = MutableStateFlow(readProfile())
  private val _revision = MutableStateFlow(0)
  override val profile: StateFlow<Profile?> = _profile.asStateFlow()
  override val revision: StateFlow<Int> = _revision.asStateFlow()

  override fun saveProfile(profile: Profile) {
    val previous = _profile.value
    val samePlace = previous != null && previous.userId == profile.userId && previous.householdId == profile.householdId
    // A different person or household starts the change feed from the beginning.
    if (!samePlace) prefs.edit().remove("sync_cursor").apply()
    prefs.edit()
      .putString("server_url", profile.serverUrl)
      .putString("user_id", profile.userId)
      .putString("display_name", profile.displayName)
      .putString("household_id", profile.householdId)
      .putString("household_name", profile.householdName)
      .putString("timezone", profile.timezone)
      .putString("role", profile.role)
      .apply()
    _profile.value = profile
  }

  override fun clearProfile() {
    // The server URL is kept to save typing at the next login.
    prefs.edit()
      .remove("user_id").remove("display_name").remove("household_id")
      .remove("household_name").remove("timezone").remove("role").remove("summary").remove("summary_today").remove("balances").remove("budgets").remove("due").remove("sync_cursor").remove("notification_prefs").remove("notified").remove("last_reminder")
      .remove("last_account_id").remove("last_sync_at")
      .apply()
    _profile.value = null
    _revision.value++
  }

  val lastServerUrl: String?
    get() = prefs.getString("server_url", null)

  override var lastAccountId: String?
    get() = prefs.getString("last_account_id", null)
    set(value) = prefs.edit().putString("last_account_id", value).apply()

  override var lastSyncAtMs: Long
    get() = prefs.getLong("last_sync_at", 0L)
    set(value) {
      prefs.edit().putLong("last_sync_at", value).apply()
      _revision.value++
    }

  override var cachedSummary: SummaryDto?
    get() = prefs.getString("summary", null)?.let { runCatching { json.decodeFromString(SummaryDto.serializer(), it) }.getOrNull() }
    set(value) {
      prefs.edit().putString("summary", value?.let { json.encodeToString(SummaryDto.serializer(), it) }).apply()
      _revision.value++
    }

  override var cachedTodaySummary: SummaryDto?
    get() = prefs.getString("summary_today", null)?.let { runCatching { json.decodeFromString(SummaryDto.serializer(), it) }.getOrNull() }
    set(value) {
      prefs.edit().putString("summary_today", value?.let { json.encodeToString(SummaryDto.serializer(), it) }).apply()
      _revision.value++
    }

  override var cachedBalances: BalancesDto?
    get() = prefs.getString("balances", null)?.let { runCatching { json.decodeFromString(BalancesDto.serializer(), it) }.getOrNull() }
    set(value) {
      prefs.edit().putString("balances", value?.let { json.encodeToString(BalancesDto.serializer(), it) }).apply()
      _revision.value++
    }

  override var cachedBudgets: BudgetStatusDto?
    get() = prefs.getString("budgets", null)?.let { runCatching { json.decodeFromString(BudgetStatusDto.serializer(), it) }.getOrNull() }
    set(value) {
      prefs.edit().putString("budgets", value?.let { json.encodeToString(BudgetStatusDto.serializer(), it) }).apply()
      _revision.value++
    }

  override var cachedDue: List<bd.family.expenses.data.remote.OccurrenceDto>?
    get() =
      prefs.getString("due", null)?.let {
        runCatching { json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(bd.family.expenses.data.remote.OccurrenceDto.serializer()), it) }.getOrNull()
      }
    set(value) {
      prefs.edit()
        .putString("due", value?.let { json.encodeToString(kotlinx.serialization.builtins.ListSerializer(bd.family.expenses.data.remote.OccurrenceDto.serializer()), it) })
        .apply()
      _revision.value++
    }

  override var notificationPrefs: bd.family.expenses.data.remote.NotificationPrefsDto
    get() =
      prefs.getString("notification_prefs", null)?.let {
        runCatching { json.decodeFromString(bd.family.expenses.data.remote.NotificationPrefsDto.serializer(), it) }.getOrNull()
      } ?: bd.family.expenses.data.remote.NotificationPrefsDto()
    set(value) {
      prefs.edit().putString("notification_prefs", json.encodeToString(bd.family.expenses.data.remote.NotificationPrefsDto.serializer(), value)).apply()
      _revision.value++
    }

  override var notifiedKeys: Set<String>
    get() = prefs.getStringSet("notified", emptySet())?.toSet() ?: emptySet()
    set(value) = prefs.edit().putStringSet("notified", value).apply()

  override var lastReminderDate: String?
    get() = prefs.getString("last_reminder", null)
    set(value) = prefs.edit().putString("last_reminder", value).apply()

  override var syncCursor: String?
    get() = prefs.getString("sync_cursor", null)
    set(value) = prefs.edit().putString("sync_cursor", value).apply()

  private fun readProfile(): Profile? {
    val user = prefs.getString("user_id", null) ?: return null
    return Profile(
      serverUrl = prefs.getString("server_url", null) ?: return null,
      userId = user,
      displayName = prefs.getString("display_name", "") ?: "",
      householdId = prefs.getString("household_id", null) ?: return null,
      householdName = prefs.getString("household_name", "") ?: "",
      timezone = prefs.getString("timezone", "Asia/Dhaka") ?: "Asia/Dhaka",
      role = prefs.getString("role", "member") ?: "member",
    )
  }
}
