import { useState } from 'react'
import { useHousehold } from '../auth/session'
import { presetRange, todayIn } from '../lib/dates'
import type { DateRange, PresetId } from '../lib/dates'

export type PeriodChoice = { preset: PresetId } | { preset: 'custom'; range: DateRange }

export function resolvePeriod(choice: PeriodChoice, today: string, weekStart: number): DateRange {
  return choice.preset === 'custom' ? choice.range : presetRange(choice.preset, today, weekStart)
}

/** The selected period resolved against "today" in the household's timezone. */
export function usePeriod(initial: PeriodChoice = { preset: 'this_month' }) {
  const household = useHousehold()
  const [choice, setChoice] = useState<PeriodChoice>(initial)
  const today = todayIn(household.timezone)
  return { choice, setChoice, range: resolvePeriod(choice, today, household.week_start), today }
}
