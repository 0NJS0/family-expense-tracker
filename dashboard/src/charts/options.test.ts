import { describe, expect, it } from 'vitest'
import type { CategoryTotal, TimeseriesBucket } from '../api/types'
import { SERIES_COLORS, categoryBars, timeseriesOption } from './options'

const labels = { income: 'Income', expense: 'Expenses', net: 'Net', partial: 'In progress' }

function bucket(start: string, income: number, expense: number, partial = false): TimeseriesBucket {
  return {
    start,
    end: start,
    income_poisha: income,
    expense_poisha: expense,
    net_poisha: income - expense,
    is_partial: partial,
  }
}

interface BarPoint {
  value: number
  itemStyle: { color: string; opacity: number }
}
interface Series {
  name: string
  type: string
  data: (BarPoint | number)[]
}

describe('timeseriesOption', () => {
  const buckets = [bucket('2026-10-01', 500000, 12050), bucket('2026-10-02', 0, 0, true)]
  const option = timeseriesOption(buckets, 'day', 'en', labels, 'light') as {
    xAxis: { data: string[] }
    series: Series[]
    tooltip: { formatter: (p: unknown) => string }
  }

  it('maps every backend bucket, including empty ones, without changing values', () => {
    expect(option.xAxis.data).toEqual(['1 Oct', '2 Oct'])
    const [income, expense, net] = option.series as [Series, Series, Series]
    expect((income.data as BarPoint[]).map((p) => p.value)).toEqual([500000, 0])
    expect((expense.data as BarPoint[]).map((p) => p.value)).toEqual([12050, 0])
    expect(net.data).toEqual([487950, 0])
  })

  it('keeps entity colours fixed and fades partial periods', () => {
    const [income, expense] = option.series
    const first = income?.data[0] as BarPoint
    const partial = expense?.data[1] as BarPoint
    expect(first.itemStyle.color).toBe(SERIES_COLORS.light.income)
    expect(partial.itemStyle.color).toBe(SERIES_COLORS.light.expense)
    expect(first.itemStyle.opacity).toBe(1)
    expect(partial.itemStyle.opacity).toBeLessThan(1)
  })

  it('formats tooltip amounts exactly and flags partial periods', () => {
    const html = option.tooltip.formatter([{ dataIndex: 0 }])
    expect(html).toContain('৳5,000.00')
    expect(html).toContain('৳120.50')
    expect(option.tooltip.formatter([{ dataIndex: 1 }])).toContain('In progress')
  })

  it('escapes names in tooltips', () => {
    const evil = timeseriesOption(buckets, 'day', 'en', { ...labels, income: '<img src=x>' }, 'dark') as {
      tooltip: { formatter: (p: unknown) => string }
    }
    expect(evil.tooltip.formatter([{ dataIndex: 0 }])).not.toContain('<img')
  })

  it('labels months and years by calendar period', () => {
    const monthly = timeseriesOption([bucket('2026-02-01', 1, 1)], 'month', 'en', labels, 'light') as {
      xAxis: { data: string[] }
    }
    expect(monthly.xAxis.data).toEqual(['Feb 2026'])
  })
})

describe('categoryBars', () => {
  const cat = (n: number, total: number): CategoryTotal => ({
    category_id: `c${n}`,
    kind: 'expense',
    name_en: `Cat ${n}`,
    name_bn: `খাত ${n}`,
    total_poisha: total,
    transaction_count: 1,
  })

  it('sorts largest first and uses the chosen language', () => {
    const bars = categoryBars([cat(1, 100), cat(2, 300)], 'bn', 'অন্যান্য')
    expect(bars).toEqual([
      { name: 'খাত 2', total_poisha: 300 },
      { name: 'খাত 1', total_poisha: 100 },
    ])
  })

  it('folds the tail into Other with an exact integer sum', () => {
    const items = Array.from({ length: 10 }, (_, i) => cat(i, 1000 - i * 10 + 1))
    const bars = categoryBars(items, 'en', 'Other')
    expect(bars).toHaveLength(8)
    const other = bars.at(-1)
    expect(other?.name).toBe('Other')
    const tail = items.slice(7).reduce((s, c) => s + c.total_poisha, 0)
    expect(other?.total_poisha).toBe(tail)
    // Nothing is lost or double counted.
    const all = items.reduce((s, c) => s + c.total_poisha, 0)
    expect(bars.reduce((s, b) => s + b.total_poisha, 0)).toBe(all)
  })
})
