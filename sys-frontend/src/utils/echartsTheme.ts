// ================================================================
// ECharts 共享暗色主题 — 所有图表统一颜色、网格、tooltip、图例配置
// 迁移自 buildingEnergy 老项目 dashboard 图表样式体系
// ================================================================
import type { EChartsOption } from 'echarts'

// ---- 颜色令牌 ----
export const CHART_COLORS = {
  text: '#8da8c5',
  muted: '#5a7595',
  gridLine: 'rgba(0, 212, 255, 0.08)',
  gridBorder: 'rgba(0, 212, 255, 0.06)',
  cyan: '#00d4ff',
  cyanDim: '#0088b3',
  amber: '#ffb800',
  emerald: '#22d3a0',
  rose: '#FF4D6D',
  violet: '#7c5cff',
} as const

// ---- 字体栈 ----
const FONT_STACK = 'Rajdhani, DINPro, AlimamaShuHeiTi, PingFang SC, sans-serif'

// ---- baseTextStyle ----
export function chartTextStyle() {
  return { color: CHART_COLORS.text, fontFamily: FONT_STACK }
}

// ---- commonGrid ----
export function chartGrid(overrides?: Partial<Record<string, unknown>>) {
  return {
    left: 12,
    right: 20,
    top: 34,
    bottom: 28,
    containLabel: true,
    borderColor: CHART_COLORS.gridBorder,
    borderWidth: 1,
    ...overrides,
  }
}

// ---- commonTooltip ----
export function chartTooltip(): EChartsOption['tooltip'] {
  return {
    backgroundColor: 'rgba(8, 20, 40, 0.92)',
    borderColor: 'rgba(0, 212, 255, 0.5)',
    borderWidth: 1,
    textStyle: { color: '#e8f4ff', fontSize: 12, fontFamily: FONT_STACK },
    axisPointer: { type: 'shadow' as const },
  }
}

// ---- commonLegend ----
export function chartLegend(
  data: string[],
  overrides?: Partial<Record<string, unknown>>,
): EChartsOption['legend'] {
  return {
    data,
    top: 2,
    right: 0,
    textStyle: { color: CHART_COLORS.text, fontSize: 11 },
    itemWidth: 14,
    itemHeight: 4,
    itemGap: 16,
    ...overrides,
  }
}

// ---- xAxis 暗色风格 ----
export function chartXAxis(data: string[], overrides?: Record<string, unknown>) {
  return {
    type: 'category' as const,
    data,
    axisLine: { lineStyle: { color: CHART_COLORS.gridLine } },
    axisTick: { show: false },
    axisLabel: { color: CHART_COLORS.text, fontSize: 11 },
    ...overrides,
  }
}

// ---- yAxis 暗色风格 ----
export function chartYAxis(overrides?: Record<string, unknown>) {
  return {
    type: 'value' as const,
    splitLine: { lineStyle: { color: CHART_COLORS.gridLine } },
    axisLabel: { color: CHART_COLORS.muted, fontSize: 10 },
    ...overrides,
  }
}

// ---- 传统控制柱状渐变 ----
export function traditionalBarGradient() {
  return {
    type: 'linear' as const,
    x: 0, y: 0, x2: 0, y2: 1,
    colorStops: [
      { offset: 0, color: '#4a6078' },
      { offset: 1, color: '#3a4d66' },
    ],
  }
}

// ---- AI 控制柱状渐变 ----
export function aiBarGradient() {
  return {
    type: 'linear' as const,
    x: 0, y: 0, x2: 0, y2: 1,
    colorStops: [
      { offset: 0, color: '#00E5FF' },
      { offset: 1, color: '#0088b3' },
    ],
  }
}

// ---- 折线面积渐变 ----
export function lineAreaGradient(color: string) {
  return {
    type: 'linear' as const,
    x: 0, y: 0, x2: 0, y2: 1,
    colorStops: [
      { offset: 0, color: color.replace(')', ', 0.25)').replace('rgb', 'rgba') },
      { offset: 1, color: color.replace(')', ', 0.01)').replace('rgb', 'rgba') },
    ],
  }
}

// ---- 告警阈值 markLine ----
export function thresholdMarkLine(yValue: number, name: string, color = CHART_COLORS.rose) {
  return {
    silent: true,
    symbol: 'none',
    lineStyle: { color, type: 'dashed' as const, width: 1, opacity: 0.5 },
    label: { show: true, formatter: `${name} {c}`, color, fontSize: 10 },
    data: [{ yAxis: yValue, name }],
  }
}
