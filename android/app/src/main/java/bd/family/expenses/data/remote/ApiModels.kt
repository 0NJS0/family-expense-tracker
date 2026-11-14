package bd.family.expenses.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Mirrors backend/app/schemas. Money fields are Long poisha.

@Serializable
data class LoginRequest(val username: String, val password: String, @SerialName("device_name") val deviceName: String)

@Serializable data class RefreshRequest(@SerialName("refresh_token") val refreshToken: String)

@Serializable
data class TokenPair(
  @SerialName("access_token") val accessToken: String,
  @SerialName("refresh_token") val refreshToken: String,
)

@Serializable
data class MeDto(val user: UserDto, val memberships: List<MembershipDto>)

@Serializable
data class UserDto(
  val id: String,
  val username: String,
  @SerialName("display_name") val displayName: String,
  @SerialName("preferred_language") val preferredLanguage: String,
)

@Serializable
data class MembershipDto(
  @SerialName("household_id") val householdId: String,
  @SerialName("household_name") val householdName: String,
  val role: String,
)

@Serializable
data class HouseholdDto(val id: String, val name: String, val timezone: String)

@Serializable
data class AccountDto(val id: String, val name: String, val kind: String, @SerialName("is_archived") val isArchived: Boolean)

@Serializable
data class CategoryDto(
  val id: String,
  val kind: String,
  @SerialName("name_en") val nameEn: String,
  @SerialName("name_bn") val nameBn: String,
  @SerialName("is_archived") val isArchived: Boolean,
)

@Serializable
data class MemberDto(
  @SerialName("user_id") val userId: String,
  @SerialName("display_name") val displayName: String,
  @SerialName("is_active") val isActive: Boolean,
)

@Serializable
data class TransactionCreateDto(
  @SerialName("client_id") val clientId: String,
  val type: String,
  @SerialName("amount_poisha") val amountPoisha: Long,
  @SerialName("category_id") val categoryId: String?,
  @SerialName("account_id") val accountId: String,
  @SerialName("destination_account_id") val destinationAccountId: String?,
  @SerialName("occurred_at") val occurredAt: String,
  val note: String?,
  @SerialName("attributed_user_id") val attributedUserId: String?,
)

@Serializable
data class TransactionDto(
  val id: String,
  @SerialName("client_id") val clientId: String,
  val type: String,
  @SerialName("amount_poisha") val amountPoisha: Long,
  @SerialName("category_id") val categoryId: String?,
  @SerialName("account_id") val accountId: String,
  @SerialName("destination_account_id") val destinationAccountId: String?,
  @SerialName("occurred_at") val occurredAt: String,
  val note: String?,
  @SerialName("attributed_user_id") val attributedUserId: String,
  val version: Int,
  @SerialName("voided_at") val voidedAt: String?,
  @SerialName("created_by_user_id") val createdByUserId: String? = null,
)

/** One budget against this month's spending, exactly as the server computed it. */
@Serializable
data class BudgetItemDto(
  @SerialName("category_id") val categoryId: String? = null,
  @SerialName("name_en") val nameEn: String? = null,
  @SerialName("name_bn") val nameBn: String? = null,
  @SerialName("budget_poisha") val budgetPoisha: Long,
  @SerialName("spent_poisha") val spentPoisha: Long,
  @SerialName("remaining_poisha") val remainingPoisha: Long,
  @SerialName("used_basis_points") val usedBasisPoints: Long,
  /** ok, warning or over. */
  val state: String,
)

@Serializable
data class BudgetStatusDto(
  val month: String,
  @SerialName("is_partial") val isPartial: Boolean,
  val items: List<BudgetItemDto>,
)

/** A recurring entry that is due (a reminder; nothing is recorded until confirmed). */
@Serializable
data class OccurrenceDto(
  val id: String,
  @SerialName("rule_name") val ruleName: String,
  val type: String,
  @SerialName("amount_poisha") val amountPoisha: Long,
  @SerialName("due_date") val dueDate: String,
  val status: String,
)

/** This person's reminder choices in the current household (GET/PUT /me/notifications). */
@Serializable
data class NotificationPrefsDto(
  @SerialName("daily_reminder") val dailyReminder: Boolean = false,
  @SerialName("missing_entry_reminder") val missingEntryReminder: Boolean = true,
  /** "20:00:00", local time in the household timezone. */
  @SerialName("reminder_time") val reminderTime: String = "20:00:00",
  @SerialName("recurring_due") val recurringDue: Boolean = true,
  @SerialName("budget_alerts") val budgetAlerts: Boolean = true,
)

/** A budget threshold crossed (admins). */
@Serializable
data class BudgetAlertDto(
  @SerialName("category_id") val categoryId: String? = null,
  @SerialName("name_en") val nameEn: String? = null,
  @SerialName("name_bn") val nameBn: String? = null,
  val month: String,
  @SerialName("threshold_percent") val thresholdPercent: Int,
  @SerialName("budget_poisha") val budgetPoisha: Long,
  @SerialName("spent_poisha") val spentPoisha: Long,
) {
  /** Stable identity: each (scope, month, threshold) happens once on the server. */
  val key: String
    get() = "alert:${categoryId ?: "all"}:$month:$thresholdPercent"
}

/** A receipt on the server; OCR fields are suggestions only. */
@Serializable
data class ReceiptDto(
  val id: String,
  @SerialName("transaction_id") val transactionId: String,
  @SerialName("content_type") val contentType: String,
  @SerialName("ocr_status") val ocrStatus: String,
  @SerialName("ocr_amount_poisha") val ocrAmountPoisha: Long? = null,
)

/** A page of GET /sync/changes: everything changed after the cursor, voids included. */
@Serializable
data class ChangesPage(
  val items: List<TransactionDto>,
  /** Client ids of changed entries this person may no longer see (private accounts). */
  val removed: List<String> = emptyList(),
  @SerialName("next_cursor") val nextCursor: String,
  @SerialName("has_more") val hasMore: Boolean,
)

@Serializable data class TransactionPage(val items: List<TransactionDto>, val total: Int)

@Serializable
data class SummaryDto(
  @SerialName("date_from") val dateFrom: String,
  @SerialName("date_to") val dateTo: String,
  @SerialName("is_partial") val isPartial: Boolean,
  @SerialName("income_poisha") val incomePoisha: Long,
  @SerialName("expense_poisha") val expensePoisha: Long,
  @SerialName("net_poisha") val netPoisha: Long,
  @SerialName("transaction_count") val transactionCount: Int,
)

@Serializable data class ErrorEnvelope(val error: ErrorBody)

@Serializable data class ErrorBody(val code: String, val message: String)
