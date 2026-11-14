/**
 * Exact money display. The API sends integer poisha (1 taka = 100 poisha).
 * Never divide money as floating point: the taka/poisha split uses BigInt.
 * Totals are never computed here; the backend is authoritative.
 */

import type { Lang } from '../i18n/strings'

const BANGLA_DIGITS = '০১২৩৪৫৬৭৮৯'

const groupers: Record<Lang, Intl.NumberFormat> = {
  // South Asian (lakh/crore) grouping in both languages: 1,25,000.
  bn: new Intl.NumberFormat('bn-BD', { useGrouping: true }),
  en: new Intl.NumberFormat('en-IN', { useGrouping: true }),
}

export function toLocalDigits(text: string, lang: Lang): string {
  if (lang !== 'bn') return text
  return text.replace(/[0-9]/g, (d) => BANGLA_DIGITS[Number(d)] ?? d)
}

export interface FormatOptions {
  /** Show "+" for positive values (useful for changes). */
  signed?: boolean
  /** Omit the ৳ symbol. */
  bare?: boolean
}

export function formatPoisha(poisha: number, lang: Lang, options: FormatOptions = {}): string {
  if (!Number.isSafeInteger(poisha)) {
    throw new RangeError(`amount must be a safe integer number of poisha, got ${poisha}`)
  }
  const value = BigInt(poisha)
  const negative = value < 0n
  const abs = negative ? -value : value
  const whole = groupers[lang].format(abs / 100n)
  const frac = toLocalDigits((abs % 100n).toString().padStart(2, '0'), lang)
  const sign = negative ? '−' : options.signed && value > 0n ? '+' : ''
  return `${sign}${options.bare ? '' : '৳'}${whole}.${frac}`
}

/** Basis points (1250 = 12.5%) as a signed percentage with one decimal. */
export function formatBasisPoints(bp: number, lang: Lang): string {
  if (!Number.isSafeInteger(bp)) throw new RangeError('basis points must be an integer')
  const negative = bp < 0
  const abs = Math.abs(bp)
  // Round to one decimal of a percent (10 bp) with integers only, half away from zero.
  const tenths = Math.floor((abs + 5) / 10)
  const text = `${Math.floor(tenths / 10)}.${tenths % 10}%`
  const sign = negative ? '−' : bp > 0 ? '+' : ''
  return sign + toLocalDigits(text, lang)
}

// Mirrors backend/app/money.py so the user sees mistakes immediately. The
// backend still parses and validates the submitted text itself.
const TAKA_PATTERN =
  /^(\d{1,15}|\d{1,3}(?:,\d{3})+|\d{1,2}(?:,\d{2})*,\d{3})(?:\.(\d{1,2}))?$/

export const MAX_TRANSACTION_POISHA = 100_000_000_000

export type AmountCheck = { ok: true; text: string } | { ok: false; reason: 'format' | 'range' }

/**
 * Validate user-typed taka and normalise it to Latin digits for the API's
 * `amount` field. Accepts Bangla digits. Does not convert to a number.
 */
export function checkTakaInput(input: string): AmountCheck {
  const latin = input
    .trim()
    .replace(/[০-৯]/g, (d) => String(BANGLA_DIGITS.indexOf(d)))
  const match = TAKA_PATTERN.exec(latin)
  if (!match) return { ok: false, reason: 'format' }
  const whole = BigInt((match[1] ?? '0').replaceAll(',', ''))
  const frac = BigInt((match[2] ?? '').padEnd(2, '0') || '0')
  const poisha = whole * 100n + frac
  if (poisha <= 0n || poisha > BigInt(MAX_TRANSACTION_POISHA)) return { ok: false, reason: 'range' }
  return { ok: true, text: latin }
}

/** Plain "120.50" (Latin digits, no grouping) for pre-filling edit fields. */
export function poishaToInputText(poisha: number): string {
  const value = BigInt(poisha)
  return `${value / 100n}.${(value % 100n).toString().padStart(2, '0')}`
}

export const MAX_ABS_OPENING_POISHA = 10_000_000_000_000

/**
 * Parse a signed taka amount (zero and negatives allowed, as for an opening
 * balance) into integer poisha without floating point. Returns null if invalid.
 */
export function parseSignedTaka(input: string): number | null {
  const text = input.trim().replace(/[০-৯]/g, (d) => String(BANGLA_DIGITS.indexOf(d)))
  const negative = text.startsWith('-') || text.startsWith('−')
  const unsigned = negative ? text.slice(1).trim() : text
  const match = TAKA_PATTERN.exec(unsigned)
  if (!match) return null
  const whole = BigInt((match[1] ?? '0').replaceAll(',', ''))
  const frac = BigInt((match[2] ?? '').padEnd(2, '0') || '0')
  const poisha = (whole * 100n + frac) * (negative ? -1n : 1n)
  const limit = BigInt(MAX_ABS_OPENING_POISHA)
  if (poisha > limit || poisha < -limit) return null
  return Number(poisha)
}
