// Mirrors backend/app/schemas. Money fields are integer poisha.

export type Uuid = string
export type IsoDate = string
export type IsoDateTime = string
export type Role = 'admin' | 'member' | 'viewer'
export type TransactionType = 'income' | 'expense' | 'transfer'
export type CategoryKind = 'income' | 'expense'
export type AccountKind = 'cash' | 'bank' | 'mobile_wallet' | 'other'
export type Granularity = 'day' | 'week' | 'month' | 'year'
export type CompareTo = 'previous_period' | 'previous_year'

export interface TokenPair {
  token_type: 'bearer'
  access_token: string
  access_token_expires_at: IsoDateTime
  refresh_token: string
  refresh_token_expires_at: IsoDateTime
}

export interface Me {
  user: { id: Uuid; username: string; display_name: string; preferred_language: 'bn' | 'en' }
  memberships: { household_id: Uuid; household_name: string; role: Role }[]
}

export interface Household {
  id: Uuid
  name: string
  timezone: string
  currency: 'BDT'
  week_start: number
  created_at: IsoDateTime
}

export interface Member {
  user_id: Uuid
  username: string
  display_name: string
  role: Role
  is_active: boolean
  joined_at: IsoDateTime
}

export interface Category {
  id: Uuid
  kind: CategoryKind
  name_en: string
  name_bn: string
  is_default: boolean
  is_archived: boolean
}

export interface Account {
  id: Uuid
  name: string
  kind: AccountKind
  institution: string | null
  owner_user_id: Uuid | null
  is_shared: boolean
  /** Only the owner and admins see a private account. */
  is_private: boolean
  is_archived: boolean
  opening_balance: { amount_poisha: number; as_of_date: IsoDate; created_at: IsoDateTime } | null
  created_at: IsoDateTime
}

export interface AccountBalanceRow {
  account_id: Uuid
  as_of_date: IsoDate | null
  has_opening_balance: boolean
  opening_balance_poisha: number
  inflow_poisha: number
  outflow_poisha: number
  balance_poisha: number
  name: string
  kind: AccountKind
  is_shared: boolean
  is_private: boolean
  is_archived: boolean
}

export interface AccountBalances {
  as_of_date: IsoDate | null
  total_poisha: number
  accounts: AccountBalanceRow[]
}

export interface Transaction {
  id: Uuid
  client_id: Uuid
  type: TransactionType
  amount_poisha: number
  category_id: Uuid | null
  account_id: Uuid
  destination_account_id: Uuid | null
  occurred_at: IsoDateTime
  note: string | null
  created_by_user_id: Uuid
  attributed_user_id: Uuid
  version: number
  created_at: IsoDateTime
  updated_at: IsoDateTime
  voided_at: IsoDateTime | null
  voided_by_user_id: Uuid | null
  void_reason: string | null
}

export interface Page<T> {
  items: T[]
  total: number
  limit: number
  offset: number
}

export interface CategoryTotal {
  category_id: Uuid
  kind: CategoryKind
  name_en: string
  name_bn: string
  total_poisha: number
  transaction_count: number
}

export interface CategoryChange {
  category_id: Uuid
  kind: CategoryKind
  name_en: string
  name_bn: string
  current_poisha: number
  previous_poisha: number
  change_poisha: number
}

export interface Comparison {
  mode: 'calendar_months' | 'preceding_days' | 'previous_year'
  date_from: IsoDate
  date_to: IsoDate
  income_poisha: number
  expense_poisha: number
  net_poisha: number
  transfer_poisha: number
  transaction_count: number
  income_change_poisha: number
  expense_change_poisha: number
  net_change_poisha: number
  income_change_basis_points: number | null
  expense_change_basis_points: number | null
  category_changes: CategoryChange[]
}

export interface MemberTotal {
  user_id: Uuid
  display_name: string
  income_poisha: number
  expense_poisha: number
  transaction_count: number
}

export interface Summary {
  date_from: IsoDate
  date_to: IsoDate
  timezone: string
  member_id: Uuid | null
  is_partial: boolean
  income_poisha: number
  expense_poisha: number
  net_poisha: number
  transfer_poisha: number
  transaction_count: number
  expense_by_category: CategoryTotal[]
  income_by_category: CategoryTotal[]
  by_member: MemberTotal[]
  comparison: Comparison
}

export interface TimeseriesBucket {
  start: IsoDate
  end: IsoDate
  income_poisha: number
  expense_poisha: number
  net_poisha: number
  is_partial: boolean
}

export interface Timeseries {
  granularity: Granularity
  date_from: IsoDate
  date_to: IsoDate
  timezone: string
  week_start: number
  member_id: Uuid | null
  buckets: TimeseriesBucket[]
}

export interface TransactionFilters {
  date_from?: IsoDate
  date_to?: IsoDate
  type?: TransactionType
  account_id?: Uuid
  category_id?: Uuid
  member_id?: Uuid
  q?: string
  include_voided?: boolean
}

/** PATCH body: `amount` is a decimal taka string parsed exactly by the backend. */
export interface TransactionPatch {
  version: number
  amount?: string
  category_id?: Uuid
  account_id?: Uuid
  destination_account_id?: Uuid
  occurred_at?: IsoDateTime
  note?: string | null
  attributed_user_id?: Uuid
}

export type BudgetState = 'ok' | 'warning' | 'over'

export interface BudgetItem {
  /** null: the whole-household budget. */
  category_id: Uuid | null
  name_en: string | null
  name_bn: string | null
  budget_poisha: number
  warn_percent: number
  effective_month: IsoDate
  spent_poisha: number
  remaining_poisha: number
  used_basis_points: number
  state: BudgetState
}

export interface BudgetStatus {
  month: IsoDate
  is_partial: boolean
  items: BudgetItem[]
}

export interface BudgetAlert {
  category_id: Uuid | null
  name_en: string | null
  name_bn: string | null
  month: IsoDate
  threshold_percent: number
  budget_poisha: number
  spent_poisha: number
  created_at: IsoDateTime
}

export type Frequency = 'weekly' | 'monthly' | 'yearly'
export type EntryType = 'income' | 'expense' | 'transfer'

export interface RecurringRule {
  id: Uuid
  name: string
  type: EntryType
  amount_poisha: number
  category_id: Uuid | null
  account_id: Uuid
  destination_account_id: Uuid | null
  attributed_user_id: Uuid
  note: string | null
  frequency: Frequency
  interval: number
  start_date: IsoDate
  end_date: IsoDate | null
  auto_post: boolean
  paused: boolean
  next_due_date: IsoDate | null
  last_processed_date: IsoDate | null
  version: number
}

export interface Occurrence {
  id: Uuid
  rule_id: Uuid
  rule_name: string
  type: EntryType
  amount_poisha: number
  category_id: Uuid | null
  account_id: Uuid
  destination_account_id: Uuid | null
  due_date: IsoDate
  status: 'pending' | 'posted' | 'skipped'
  transaction_id: Uuid | null
  resolved_at: IsoDateTime | null
}

export interface Receipt {
  id: Uuid
  transaction_id: Uuid
  content_type: 'image/jpeg' | 'application/pdf'
  size_bytes: number
  uploaded_by_user_id: Uuid
  created_at: IsoDateTime
  /** OCR runs later on the homelab; its amount is only a suggestion. */
  ocr_status: 'pending' | 'done' | 'failed' | 'unavailable'
  ocr_amount_poisha: number | null
  ocr_date: IsoDate | null
}

export interface AiReport {
  id: Uuid
  period_start: IsoDate
  language: 'bn' | 'en'
  status: 'queued' | 'running' | 'done' | 'rejected' | 'failed'
  /** Only for "done": AI-written, every number checked against the figures. */
  text: string | null
  problem: string | null
  facts: Record<string, unknown> | null
  model: string | null
  attempts: number
  created_at: IsoDateTime
  finished_at: IsoDateTime | null
}
