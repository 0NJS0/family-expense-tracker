package bd.family.expenses.data

import bd.family.expenses.data.local.SyncState
import bd.family.expenses.data.local.TransactionDao
import bd.family.expenses.data.local.TransactionEntity
import bd.family.expenses.data.settings.SettingsStore
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.UUID

data class NewEntry(
  val type: String,
  val amountPoisha: Long,
  /** Required for income and expense; null for a transfer. */
  val categoryId: String?,
  /** The source account for a transfer. */
  val accountId: String,
  val date: LocalDate,
  val note: String?,
  val attributedUserId: String?,
  /** Transfers only: where the money went. Must differ from [accountId]. */
  val destinationAccountId: String? = null,
)

/**
 * Saving an entry never needs the network: it is written to the phone first
 * with a fresh client id, then a background upload is requested.
 */
class EntryRepository(
  private val transactions: TransactionDao,
  private val settings: SettingsStore,
  private val requestSync: () -> Unit,
  private val clock: Clock = Clock.systemUTC(),
  private val newId: () -> String = { UUID.randomUUID().toString() },
) {
  suspend fun save(entry: NewEntry): TransactionEntity {
    val transfer = entry.type == "transfer"
    require(transfer == (entry.destinationAccountId != null)) { "only transfers have a destination" }
    require(transfer == (entry.categoryId == null)) { "transfers have no category; others need one" }
    require(entry.destinationAccountId != entry.accountId) { "transfer to the same account" }
    val profile = checkNotNull(settings.profile.value) { "not signed in" }
    val zone = ZoneId.of(profile.timezone)
    val occurred = occurrence(entry.date, zone, clock)
    val entity =
      TransactionEntity(
        clientId = newId(),
        householdId = profile.householdId,
        serverId = null,
        type = entry.type,
        amountPoisha = entry.amountPoisha,
        categoryId = entry.categoryId,
        accountId = entry.accountId,
        destinationAccountId = entry.destinationAccountId,
        occurredAt = occurred.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
        occurredAtEpochMs = occurred.toInstant().toEpochMilli(),
        note = entry.note?.trim()?.takeIf { it.isNotEmpty() },
        attributedUserId = entry.attributedUserId?.takeIf { it != profile.userId },
        createdByUserId = profile.userId,
        syncState = SyncState.PENDING,
        createdLocallyAtMs = clock.millis(),
      )
    transactions.insert(entity)
    if (!transfer) settings.lastAccountId = entry.accountId
    requestSync()
    return entity
  }
}

/** Today: the current time. An earlier day: midday, so it can never slip into a neighbouring day. */
internal fun occurrence(date: LocalDate, zone: ZoneId, clock: Clock): ZonedDateTime {
  val now = ZonedDateTime.now(clock.withZone(zone)).truncatedTo(ChronoUnit.SECONDS)
  return if (date == now.toLocalDate()) now else ZonedDateTime.of(date, LocalTime.NOON, zone)
}
