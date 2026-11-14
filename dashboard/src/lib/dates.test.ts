import { describe, expect, it } from 'vitest'
import {
  addDays,
  endOfMonth,
  fromLocalInput,
  isIsoDate,
  overviewGranularity,
  presetRange,
  startOfWeek,
  toLocalInput,
  todayIn,
} from './dates'

const SATURDAY = 5

describe('todayIn', () => {
  it('uses the household timezone, not UTC', () => {
    // 18:30 UTC on 31 Jan is 00:30 on 1 Feb in Dhaka.
    expect(todayIn('Asia/Dhaka', new Date('2026-01-31T18:30:00Z'))).toBe('2026-02-01')
    expect(todayIn('Asia/Dhaka', new Date('2026-01-31T17:59:59Z'))).toBe('2026-01-31')
  })
})

describe('calendar arithmetic', () => {
  it('handles month and leap-year ends', () => {
    expect(endOfMonth('2026-02-10')).toBe('2026-02-28')
    expect(endOfMonth('2024-02-10')).toBe('2024-02-29')
    expect(addDays('2026-12-31', 1)).toBe('2027-01-01')
  })

  it('validates real dates only', () => {
    expect(isIsoDate('2026-02-28')).toBe(true)
    expect(isIsoDate('2026-02-30')).toBe(false)
    expect(isIsoDate('15/10/2026')).toBe(false)
  })

  it('starts weeks on the household week start', () => {
    // 2026-10-15 is a Thursday; the Saturday before is 2026-10-10.
    expect(startOfWeek('2026-10-15', SATURDAY)).toBe('2026-10-10')
    expect(startOfWeek('2026-10-10', SATURDAY)).toBe('2026-10-10')
    expect(startOfWeek('2026-10-15', 0)).toBe('2026-10-12') // Monday
  })
})

describe('presetRange', () => {
  const today = '2026-10-15'
  it.each([
    ['this_week', '2026-10-10', '2026-10-15'],
    ['this_month', '2026-10-01', '2026-10-15'],
    ['last_month', '2026-09-01', '2026-09-30'],
    ['last_30_days', '2026-09-16', '2026-10-15'],
    ['this_year', '2026-01-01', '2026-10-15'],
  ] as const)('%s', (preset, from, to) => {
    expect(presetRange(preset, today, SATURDAY)).toEqual({ from, to })
  })

  it('last month crosses the year boundary', () => {
    expect(presetRange('last_month', '2026-01-05', SATURDAY)).toEqual({
      from: '2025-12-01',
      to: '2025-12-31',
    })
  })
})

describe('local datetime inputs', () => {
  it('round-trips through the household timezone', () => {
    const instant = '2026-03-05T17:30:00Z' // 23:30 in Dhaka
    expect(toLocalInput(instant, 'Asia/Dhaka')).toBe('2026-03-05T23:30')
    expect(fromLocalInput('2026-03-05T23:30', 'Asia/Dhaka')).toBe('2026-03-05T23:30:00+06:00')
    expect(Date.parse(fromLocalInput('2026-03-05T23:30', 'Asia/Dhaka'))).toBe(Date.parse(instant))
  })
})

describe('overviewGranularity', () => {
  it.each([
    [{ from: '2026-10-01', to: '2026-10-31' }, 'day'],
    [{ from: '2026-07-01', to: '2026-10-15' }, 'week'],
    [{ from: '2026-01-01', to: '2026-12-31' }, 'month'],
  ] as const)('%j → %s', (range, expected) => {
    expect(overviewGranularity(range)).toBe(expected)
  })
})
