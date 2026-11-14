/**
 * Calendar dates are plain "YYYY-MM-DD" strings in the household timezone,
 * matching the API. Arithmetic is done on UTC midnights, which has no DST or
 * offset surprises, and is never mixed with the browser's local timezone.
 */

import type { Granularity } from '../api/types'
import type { Lang } from '../i18n/strings'

export type IsoDate = string

export interface DateRange {
  from: IsoDate
  to: IsoDate
}

const ISO = /^\d{4}-\d{2}-\d{2}$/

export function isIsoDate(value: string): boolean {
  if (!ISO.test(value)) return false
  return toUtc(value).toISOString().slice(0, 10) === value
}

function toUtc(day: IsoDate): Date {
  return new Date(`${day}T00:00:00Z`)
}

function fromUtc(date: Date): IsoDate {
  return date.toISOString().slice(0, 10)
}

export function todayIn(timeZone: string, now: Date = new Date()): IsoDate {
  // en-CA formats as YYYY-MM-DD.
  return new Intl.DateTimeFormat('en-CA', { timeZone }).format(now)
}

export function addDays(day: IsoDate, days: number): IsoDate {
  const d = toUtc(day)
  d.setUTCDate(d.getUTCDate() + days)
  return fromUtc(d)
}

export function startOfMonth(day: IsoDate): IsoDate {
  return `${day.slice(0, 7)}-01`
}

export function endOfMonth(day: IsoDate): IsoDate {
  const d = toUtc(startOfMonth(day))
  d.setUTCMonth(d.getUTCMonth() + 1)
  d.setUTCDate(0)
  return fromUtc(d)
}

/** Monday = 0 … Sunday = 6, as the API's household.week_start. */
export function weekday(day: IsoDate): number {
  return (toUtc(day).getUTCDay() + 6) % 7
}

export function startOfWeek(day: IsoDate, weekStart: number): IsoDate {
  return addDays(day, -((weekday(day) - weekStart + 7) % 7))
}

export type PresetId = 'this_week' | 'this_month' | 'last_month' | 'last_30_days' | 'this_year'

export const PRESETS: PresetId[] = [
  'this_week',
  'this_month',
  'last_month',
  'last_30_days',
  'this_year',
]

export function presetRange(preset: PresetId, today: IsoDate, weekStart: number): DateRange {
  switch (preset) {
    case 'this_week':
      return { from: startOfWeek(today, weekStart), to: today }
    case 'this_month':
      return { from: startOfMonth(today), to: today }
    case 'last_month': {
      const lastDay = addDays(startOfMonth(today), -1)
      return { from: startOfMonth(lastDay), to: lastDay }
    }
    case 'last_30_days':
      return { from: addDays(today, -29), to: today }
    case 'this_year':
      return { from: `${today.slice(0, 4)}-01-01`, to: today }
  }
}

const dateFormats: Record<Lang, string> = { bn: 'bn-BD', en: 'en-GB' }

export function formatDate(day: IsoDate, lang: Lang): string {
  return new Intl.DateTimeFormat(dateFormats[lang], {
    day: 'numeric',
    month: 'short',
    year: 'numeric',
    timeZone: 'UTC',
  }).format(toUtc(day))
}

export function formatDateTime(instant: string, lang: Lang, timeZone: string): string {
  return new Intl.DateTimeFormat(dateFormats[lang], {
    day: 'numeric',
    month: 'short',
    year: 'numeric',
    hour: 'numeric',
    minute: '2-digit',
    timeZone,
  }).format(new Date(instant))
}

/** The household-local wall time of an instant, for <input type="datetime-local">. */
export function toLocalInput(instant: string, timeZone: string): string {
  const parts = new Intl.DateTimeFormat('en-CA', {
    timeZone,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hourCycle: 'h23',
  }).formatToParts(new Date(instant))
  const get = (type: string) => parts.find((p) => p.type === type)?.value ?? '00'
  return `${get('year')}-${get('month')}-${get('day')}T${get('hour')}:${get('minute')}`
}

/** Offset like "+06:00" of ``timeZone`` at ``instant``. */
export function offsetOf(timeZone: string, instant: Date): string {
  const name =
    new Intl.DateTimeFormat('en-US', { timeZone, timeZoneName: 'longOffset' })
      .formatToParts(instant)
      .find((p) => p.type === 'timeZoneName')?.value ?? 'GMT'
  const match = /GMT([+-]\d{2}:\d{2})?/.exec(name)
  return match?.[1] ?? '+00:00'
}

/** Turn a household-local "YYYY-MM-DDTHH:mm" into an ISO instant with offset. */
export function fromLocalInput(local: string, timeZone: string): string {
  // Asia/Dhaka has no DST, so the offset at the given wall time is unambiguous.
  const guess = new Date(`${local}:00Z`)
  return `${local}:00${offsetOf(timeZone, guess)}`
}

/** A sensible bucket size for the overview chart, given the period length. */
export function overviewGranularity(range: DateRange): Granularity {
  const days =
    (Date.parse(`${range.to}T00:00:00Z`) - Date.parse(`${range.from}T00:00:00Z`)) / 86_400_000 + 1
  if (days <= 45) return 'day'
  if (days <= 190) return 'week'
  return 'month'
}

/** "2026-10" plus `months` (may be negative). */
export function addMonths(month: string, months: number): string {
  const [y, m] = month.split('-').map(Number) as [number, number]
  const index = y * 12 + (m - 1) + months
  return `${Math.floor(index / 12)}-${String((index % 12) + 1).padStart(2, '0')}`
}

/** "অক্টোবর ২০২৬" / "October 2026" for a "YYYY-MM" month. */
export function formatMonth(month: string, lang: Lang): string {
  return new Intl.DateTimeFormat(lang === 'bn' ? 'bn-BD' : 'en-GB', {
    month: 'long',
    year: 'numeric',
    timeZone: 'UTC',
  }).format(new Date(`${month}-01T00:00:00Z`))
}
