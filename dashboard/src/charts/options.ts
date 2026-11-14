/**
 * Pure mapping from backend report data to ECharts options. All values stay in
 * integer poisha; formatting for people goes through formatPoisha.
 *
 * Colour follows the entity, never its rank: income, expense and net always
 * use the same slots (validated palette, see docs/dashboard.md).
 */

import type { EChartsCoreOption } from 'echarts/core'
import type { CategoryTotal, Granularity, TimeseriesBucket } from '../api/types'
import type { Lang } from '../i18n/strings'
import { formatPoisha, toLocalDigits } from '../lib/money'

export type Scheme = 'light' | 'dark'

export const SERIES_COLORS: Record<Scheme, { income: string; expense: string; net: string }> = {
  light: { income: '#2a78d6', expense: '#eb6834', net: '#1baf7a' },
  dark: { income: '#3987e5', expense: '#d95926', net: '#199e70' },
}

const INK: Record<Scheme, { text: string; muted: string; grid: string; surface: string }> = {
  light: { text: '#0b0b0b', muted: '#52514e', grid: '#e4e3df', surface: '#fcfcfb' },
  dark: { text: '#f0efec', muted: '#c3c2b7', grid: '#3a3a37', surface: '#1a1a19' },
}

const LOCALE: Record<Lang, string> = { bn: 'bn-BD', en: 'en-GB' }

export function bucketLabel(start: string, granularity: Granularity, lang: Lang): string {
  const date = new Date(`${start}T00:00:00Z`)
  const opts: Intl.DateTimeFormatOptions =
    granularity === 'year'
      ? { year: 'numeric', timeZone: 'UTC' }
      : granularity === 'month'
        ? { month: 'short', year: 'numeric', timeZone: 'UTC' }
        : { day: 'numeric', month: 'short', timeZone: 'UTC' }
  return new Intl.DateTimeFormat(LOCALE[lang], opts).format(date)
}

/**
 * Axis ticks are positions chosen by the chart library, not amounts, so a
 * compact approximate label is appropriate here (and only here).
 */
export function axisLabel(poisha: number, lang: Lang): string {
  return new Intl.NumberFormat(LOCALE[lang], { notation: 'compact', maximumFractionDigits: 1 })
    .format(poisha / 100)
}

export interface SeriesLabels {
  income: string
  expense: string
  net: string
  partial: string
}

export function timeseriesOption(
  buckets: TimeseriesBucket[],
  granularity: Granularity,
  lang: Lang,
  labels: SeriesLabels,
  scheme: Scheme,
): EChartsCoreOption {
  const colors = SERIES_COLORS[scheme]
  const ink = INK[scheme]
  const categories = buckets.map((b) => bucketLabel(b.start, granularity, lang))
  // An in-progress period is drawn faded so it is not read as a complete one.
  const bar = (value: number, partial: boolean, color: string) => ({
    value,
    itemStyle: { color, opacity: partial ? 0.45 : 1, borderRadius: [4, 4, 0, 0] },
  })
  return {
    animation: false,
    color: [colors.income, colors.expense, colors.net],
    textStyle: { color: ink.text },
    legend: { top: 0, textStyle: { color: ink.muted } },
    grid: { left: 8, right: 8, top: 36, bottom: 8, containLabel: true },
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'line' },
      formatter: (params: unknown) => tooltipHtml(params, buckets, lang, labels),
    },
    xAxis: {
      type: 'category',
      data: categories,
      axisLine: { lineStyle: { color: ink.grid } },
      axisLabel: { color: ink.muted, hideOverlap: true },
      axisTick: { show: false },
    },
    yAxis: {
      type: 'value',
      splitLine: { lineStyle: { color: ink.grid } },
      axisLabel: { color: ink.muted, formatter: (v: number) => axisLabel(v, lang) },
    },
    series: [
      {
        name: labels.income,
        type: 'bar',
        barGap: '10%',
        barMaxWidth: 28,
        data: buckets.map((b) => bar(b.income_poisha, b.is_partial, colors.income)),
      },
      {
        name: labels.expense,
        type: 'bar',
        barMaxWidth: 28,
        data: buckets.map((b) => bar(b.expense_poisha, b.is_partial, colors.expense)),
      },
      {
        name: labels.net,
        type: 'line',
        symbolSize: 8,
        lineStyle: { width: 2 },
        data: buckets.map((b) => b.net_poisha),
      },
    ],
  }
}

function tooltipHtml(
  params: unknown,
  buckets: TimeseriesBucket[],
  lang: Lang,
  labels: SeriesLabels,
): string {
  const first = Array.isArray(params) ? (params[0] as { dataIndex?: number }) : undefined
  const bucket = first?.dataIndex === undefined ? undefined : buckets[first.dataIndex]
  if (!bucket) return ''
  const rows = [
    [labels.income, bucket.income_poisha],
    [labels.expense, bucket.expense_poisha],
    [labels.net, bucket.net_poisha],
  ] as const
  const range =
    bucket.start === bucket.end
      ? toLocalDigits(bucket.start, lang)
      : `${toLocalDigits(bucket.start, lang)} – ${toLocalDigits(bucket.end, lang)}`
  return [
    `<strong>${range}</strong>`,
    ...rows.map(([name, value]) => `${escapeHtml(name)}: <b>${formatPoisha(value, lang)}</b>`),
    bucket.is_partial ? `<em>${escapeHtml(labels.partial)}</em>` : '',
  ]
    .filter(Boolean)
    .join('<br/>')
}

export const MAX_CATEGORY_BARS = 8

export interface CategoryBar {
  name: string
  total_poisha: number
}

/**
 * Largest categories first; anything beyond the first seven is folded into
 * "Other" so the chart never needs more than eight bars. The fold is a sum
 * of backend-provided integers, done with exact integer arithmetic.
 */
export function categoryBars(items: CategoryTotal[], lang: Lang, otherLabel: string): CategoryBar[] {
  const sorted = [...items].sort((a, b) => b.total_poisha - a.total_poisha)
  const named = (c: CategoryTotal) => (lang === 'bn' ? c.name_bn : c.name_en)
  if (sorted.length <= MAX_CATEGORY_BARS) {
    return sorted.map((c) => ({ name: named(c), total_poisha: c.total_poisha }))
  }
  const head = sorted.slice(0, MAX_CATEGORY_BARS - 1)
  const rest = sorted.slice(MAX_CATEGORY_BARS - 1)
  const other = rest.reduce((sum, c) => sum + BigInt(c.total_poisha), 0n)
  return [
    ...head.map((c) => ({ name: named(c), total_poisha: c.total_poisha })),
    { name: otherLabel, total_poisha: Number(other) },
  ]
}

export function categoryOption(bars: CategoryBar[], lang: Lang, scheme: Scheme): EChartsCoreOption {
  const ink = INK[scheme]
  const color = SERIES_COLORS[scheme].expense
  // Horizontal bars, largest at the top: magnitude comparison, readable names.
  const ordered = [...bars].reverse()
  return {
    animation: false,
    textStyle: { color: ink.text },
    grid: { left: 8, right: 96, top: 8, bottom: 8, containLabel: true },
    tooltip: {
      trigger: 'item',
      formatter: (p: { dataIndex: number }) => {
        const bar = ordered[p.dataIndex]
        return bar ? `${escapeHtml(bar.name)}: <b>${formatPoisha(bar.total_poisha, lang)}</b>` : ''
      },
    },
    xAxis: { type: 'value', show: false },
    yAxis: {
      type: 'category',
      data: ordered.map((b) => b.name),
      axisLine: { show: false },
      axisTick: { show: false },
      axisLabel: { color: ink.text },
    },
    series: [
      {
        type: 'bar',
        barMaxWidth: 22,
        itemStyle: { color, borderRadius: [0, 4, 4, 0] },
        label: {
          show: true,
          position: 'right',
          color: ink.muted,
          formatter: (p: { dataIndex: number }) => {
            const bar = ordered[p.dataIndex]
            return bar ? formatPoisha(bar.total_poisha, lang) : ''
          },
        },
        data: ordered.map((b) => b.total_poisha),
      },
    ],
  }
}

function escapeHtml(text: string): string {
  return text
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;')
}
