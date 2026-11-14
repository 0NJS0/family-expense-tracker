package bd.family.expenses.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// --- Response types for the admin screens (mirror backend/app/schemas) -------------

@Serializable
data class CategoryTotalDto(
  @SerialName("category_id") val categoryId: String,
  val kind: String,
  @SerialName("name_en") val nameEn: String,
  @SerialName("name_bn") val nameBn: String,
  @SerialName("total_poisha") val totalPoisha: Long,
  @SerialName("transaction_count") val transactionCount: Int,
)

@Serializable
data class MemberTotalDto(
  @SerialName("user_id") val userId: String,
  @SerialName("display_name") val displayName: String,
  @SerialName("income_poisha") val incomePoisha: Long,
  @SerialName("expense_poisha") val expensePoisha: Long,
)

@Serializable
data class ComparisonDto(
  @SerialName("date_from") val dateFrom: String,
  @SerialName("date_to") val dateTo: String,
  @SerialName("transaction_count") val transactionCount: Int,
  @SerialName("income_change_poisha") val incomeChangePoisha: Long,
  @SerialName("expense_change_poisha") val expenseChangePoisha: Long,
  @SerialName("expense_change_basis_points") val expenseChangeBasisPoints: Int?,
)

@Serializable
data class FullSummaryDto(
  @SerialName("date_from") val dateFrom: String,
  @SerialName("date_to") val dateTo: String,
  @SerialName("is_partial") val isPartial: Boolean,
  @SerialName("income_poisha") val incomePoisha: Long,
  @SerialName("expense_poisha") val expensePoisha: Long,
  @SerialName("net_poisha") val netPoisha: Long,
  @SerialName("transfer_poisha") val transferPoisha: Long,
  @SerialName("transaction_count") val transactionCount: Int,
  @SerialName("expense_by_category") val expenseByCategory: List<CategoryTotalDto>,
  @SerialName("by_member") val byMember: List<MemberTotalDto> = emptyList(),
  val comparison: ComparisonDto,
)

@Serializable
data class BalanceRowDto(
  @SerialName("account_id") val accountId: String,
  val name: String,
  val kind: String,
  @SerialName("is_archived") val isArchived: Boolean,
  @SerialName("balance_poisha") val balancePoisha: Long,
)

@Serializable
data class BalancesDto(@SerialName("total_poisha") val totalPoisha: Long, val accounts: List<BalanceRowDto>)

@Serializable
data class OpeningDto(@SerialName("amount_poisha") val amountPoisha: Long, @SerialName("as_of_date") val asOfDate: String)

@Serializable
data class AccountFullDto(
  val id: String,
  val name: String,
  val kind: String,
  val institution: String? = null,
  @SerialName("is_archived") val isArchived: Boolean,
  @SerialName("opening_balance") val openingBalance: OpeningDto? = null,
  /** Seen only by its owner and admins (set on the dashboard). */
  @SerialName("is_private") val isPrivate: Boolean = false,
)

@Serializable
data class MemberFullDto(
  @SerialName("user_id") val userId: String,
  val username: String,
  @SerialName("display_name") val displayName: String,
  val role: String,
  @SerialName("is_active") val isActive: Boolean,
)

@Serializable
data class InvitationDto(
  @SerialName("display_name") val displayName: String,
  val token: String,
  @SerialName("expires_at") val expiresAt: String,
)

@Serializable
data class AdminTransactionDto(
  val id: String,
  val type: String,
  @SerialName("amount_poisha") val amountPoisha: Long,
  @SerialName("category_id") val categoryId: String? = null,
  @SerialName("account_id") val accountId: String,
  @SerialName("destination_account_id") val destinationAccountId: String? = null,
  @SerialName("occurred_at") val occurredAt: String,
  val note: String? = null,
  @SerialName("attributed_user_id") val attributedUserId: String,
  @SerialName("created_by_user_id") val createdByUserId: String,
  val version: Int,
  @SerialName("voided_at") val voidedAt: String? = null,
  @SerialName("void_reason") val voidReason: String? = null,
)

@Serializable data class AdminTransactionPage(val items: List<AdminTransactionDto>, val total: Int)

/**
 * The dashboard's management endpoints, for admins on the phone. These are
 * online-only: they change shared household data directly (no offline outbox).
 * The server enforces permissions; the phone only hides what a role cannot do.
 */
class AdminApi(private val session: Session) {
  private fun body(obj: JsonObject) = json.encodeToString(JsonObject.serializer(), obj)

  suspend fun summary(from: String, to: String): FullSummaryDto =
    session.request("GET", "/reports/summary", FullSummaryDto.serializer(), query = mapOf("date_from" to from, "date_to" to to))

  /** Monthly AI summaries written by the homelab worker (admins). */
  suspend fun aiReports(): List<AiReportDto> =
    session.request("GET", "/ai-reports", kotlinx.serialization.builtins.ListSerializer(AiReportDto.serializer()))

  suspend fun balances(): BalancesDto = session.request("GET", "/accounts/balances", BalancesDto.serializer())

  suspend fun transactions(
    from: String,
    to: String,
    type: String?,
    offset: Int,
    limit: Int = PAGE,
    largestFirst: Boolean = false,
  ): AdminTransactionPage {
    val query = buildMap {
      put("date_from", from)
      put("date_to", to)
      put("offset", "$offset")
      put("limit", "$limit")
      if (type != null) put("type", type)
      if (largestFirst) put("sort", "largest")
    }
    return session.request("GET", "/transactions", AdminTransactionPage.serializer(), query = query)
  }

  suspend fun transaction(id: String): AdminTransactionDto =
    session.request("GET", "/transactions/$id", AdminTransactionDto.serializer())

  /** Changes only the given fields; [version] must be the one shown to the admin. */
  suspend fun updateTransaction(
    id: String,
    version: Int,
    amountPoisha: Long? = null,
    categoryId: String? = null,
    accountId: String? = null,
    note: String? = null,
    clearNote: Boolean = false,
  ): AdminTransactionDto =
    session.request(
      "PATCH",
      "/transactions/$id",
      AdminTransactionDto.serializer(),
      body(
        buildJsonObject {
          put("version", version)
          amountPoisha?.let { put("amount_poisha", it) }
          categoryId?.let { put("category_id", it) }
          accountId?.let { put("account_id", it) }
          if (clearNote) put("note", null as String?) else note?.let { put("note", it) }
        }
      ),
    )

  suspend fun voidTransaction(id: String, version: Int, reason: String): AdminTransactionDto =
    session.request(
      "POST",
      "/transactions/$id/void",
      AdminTransactionDto.serializer(),
      body(buildJsonObject { put("version", version); put("reason", reason) }),
    )

  suspend fun accounts(): List<AccountFullDto> =
    session.request("GET", "/accounts", ListSerializer(AccountFullDto.serializer()), query = mapOf("include_archived" to "true"))

  suspend fun createAccount(name: String, kind: String, provider: String?, openingPoisha: Long?, openingDate: String?) {
    session.request(
      "POST",
      "/accounts",
      AccountFullDto.serializer(),
      body(
        buildJsonObject {
          put("name", name)
          put("kind", kind)
          provider?.let { put("institution", it) }
          if (openingPoisha != null && openingDate != null) {
            put("opening_balance", buildJsonObject { put("amount_poisha", openingPoisha); put("as_of_date", openingDate) })
          }
        }
      ),
    )
  }

  suspend fun updateAccount(id: String, name: String? = null, provider: String? = null, archived: Boolean? = null) {
    session.request(
      "PATCH",
      "/accounts/$id",
      AccountFullDto.serializer(),
      body(
        buildJsonObject {
          name?.let { put("name", it) }
          provider?.let { put("institution", it) }
          archived?.let { put("is_archived", it) }
        }
      ),
    )
  }

  suspend fun categories(): List<CategoryDto> =
    session.request("GET", "/categories", ListSerializer(CategoryDto.serializer()), query = mapOf("include_archived" to "true"))

  suspend fun createCategory(kind: String, nameBn: String, nameEn: String) {
    session.request(
      "POST",
      "/categories",
      CategoryDto.serializer(),
      body(buildJsonObject { put("kind", kind); put("name_bn", nameBn); put("name_en", nameEn) }),
    )
  }

  suspend fun updateCategory(id: String, nameBn: String? = null, nameEn: String? = null, archived: Boolean? = null) {
    session.request(
      "PATCH",
      "/categories/$id",
      CategoryDto.serializer(),
      body(
        buildJsonObject {
          nameBn?.let { put("name_bn", it) }
          nameEn?.let { put("name_en", it) }
          archived?.let { put("is_archived", it) }
        }
      ),
    )
  }

  suspend fun members(): List<MemberFullDto> =
    session.request("GET", "/members", ListSerializer(MemberFullDto.serializer()))

  suspend fun updateMember(userId: String, role: String? = null, active: Boolean? = null) {
    session.request(
      "PATCH",
      "/members/$userId",
      MemberFullDto.serializer(),
      body(buildJsonObject { role?.let { put("role", it) }; active?.let { put("is_active", it) } }),
    )
  }

  suspend fun invite(displayName: String, role: String): InvitationDto =
    session.request(
      "POST",
      "/members/invitations",
      InvitationDto.serializer(),
      body(buildJsonObject { put("display_name", displayName); put("role", role) }),
    )

  companion object {
    const val PAGE = 25
  }
}

/** Only "done" reports carry text: every number in it was checked against the figures. */
@Serializable
data class AiReportDto(
  val id: String,
  @SerialName("period_start") val periodStart: String,
  val language: String,
  val status: String,
  val text: String? = null,
)
