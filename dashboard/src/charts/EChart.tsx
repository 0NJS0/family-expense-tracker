import { BarChart, LineChart } from 'echarts/charts'
import { GridComponent, LegendComponent, TooltipComponent } from 'echarts/components'
import { init, use as registerEChartsParts } from 'echarts/core'
import type { EChartsCoreOption, EChartsType } from 'echarts/core'
import { SVGRenderer } from 'echarts/renderers'
import { useEffect, useRef } from 'react'

// Register only what the dashboard uses, to keep the bundle small.
registerEChartsParts([BarChart, LineChart, GridComponent, LegendComponent, TooltipComponent, SVGRenderer])

interface Props {
  option: EChartsCoreOption
  height: number
  /** Accessible summary; the visual chart is hidden from screen readers. */
  label: string
}

export function EChart({ option, height, label }: Props) {
  const ref = useRef<HTMLDivElement>(null)
  const chart = useRef<EChartsType | null>(null)

  useEffect(() => {
    const el = ref.current
    if (!el) return undefined
    const instance = init(el, undefined, { renderer: 'svg' })
    chart.current = instance
    const observer = new ResizeObserver(() => instance.resize())
    observer.observe(el)
    return () => {
      observer.disconnect()
      instance.dispose()
      chart.current = null
    }
  }, [])

  useEffect(() => {
    chart.current?.setOption(option, { notMerge: true })
  }, [option])

  return (
    <figure className="chart" aria-label={label}>
      <div ref={ref} style={{ height }} aria-hidden="true" />
    </figure>
  )
}
