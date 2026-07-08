<script setup lang="ts">
// ================================================================
// CompareCharts — AI 控制效果对比
// 左：AI 控制前后指标对比柱状图 | 右：拥堵指数实时变化折线图
// ================================================================
import { onMounted, onBeforeUnmount, watch, ref } from 'vue'
import { storeToRefs } from 'pinia'
import * as echarts from 'echarts'
import type { EChartsOption } from 'echarts'
import { useTrafficStore } from '@/stores/traffic'
import type { CompareMetrics, CongestionTrendPoint } from '@/types/traffic'
import {
  CHART_COLORS,
  chartTextStyle,
  chartGrid,
  chartTooltip,
  chartLegend,
  chartXAxis,
  chartYAxis,
  traditionalBarGradient,
  aiBarGradient,
  thresholdMarkLine,
} from '@/utils/echartsTheme'

const store = useTrafficStore()
const { compareMetrics, congestionTrend } = storeToRefs(store)

// ---- DOM 引用 ----
const barContainer = ref<HTMLDivElement | null>(null)
const lineContainer = ref<HTMLDivElement | null>(null)

let barChart: echarts.ECharts | null = null
let lineChart: echarts.ECharts | null = null

// ================================================================
// 图表 1：AI 控制前后指标对比（分组柱状图）
// ================================================================
function buildBarOption(m: CompareMetrics): EChartsOption {
  const items = [m.averageWaitTime, m.averageSpeed, m.queueLength, m.emergencyPassTime]
  const names = items.map((it) => it.name)
  const traditional = items.map((it) => it.traditional)
  const ai = items.map((it) => it.ai)

  return {
    backgroundColor: 'transparent',
    textStyle: chartTextStyle(),
    tooltip: {
      ...chartTooltip(),
      trigger: 'axis',
      formatter: (params: unknown) => {
        const arr = params as Array<{ seriesName: string; name: string; value: number; color: string }>
        let html = `<div style="font-weight:700;margin-bottom:6px">${arr[0]?.name ?? ''}</div>`
        for (const p of arr) {
          const it = items.find((x) => x.name === p.name)
          html += `<div style="color:${p.color};margin-top:3px">${p.seriesName}: ${p.value} ${it?.unit ?? ''}</div>`
        }
        return html
      },
    },
    legend: chartLegend(['传统控制', 'AI 自适应']),
    grid: chartGrid(),
    xAxis: chartXAxis(names),
    yAxis: chartYAxis(),
    series: [
      {
        name: '传统控制',
        type: 'bar',
        data: traditional,
        barWidth: 14,
        barGap: '30%',
        itemStyle: {
          color: traditionalBarGradient(),
          borderRadius: [2, 2, 0, 0],
        },
        animationDuration: 600,
      },
      {
        name: 'AI 自适应',
        type: 'bar',
        data: ai,
        barWidth: 14,
        itemStyle: {
          color: aiBarGradient(),
          borderRadius: [2, 2, 0, 0],
        },
        emphasis: {
          itemStyle: { shadowBlur: 14, shadowColor: 'rgba(0, 212, 255, 0.6)' },
        },
        animationDuration: 700,
      },
    ],
  }
}

// ================================================================
// 图表 2：拥堵指数实时变化（折线 + 面积图）
// ================================================================
function buildLineOption(trend: CongestionTrendPoint[]): EChartsOption {
  const times = trend.map((p) => p.time)
  const values = trend.map((p) => p.value)

  return {
    backgroundColor: 'transparent',
    textStyle: chartTextStyle(),
    tooltip: {
      ...chartTooltip(),
      trigger: 'axis',
      formatter: (params: unknown) => {
        const arr = params as Array<{ name: string; value: number }>
        if (!arr[0]) return ''
        return `<div style="font-weight:700;margin-bottom:4px">${arr[0].name}</div><div style="color:${CHART_COLORS.cyan}">拥堵指数: ${arr[0].value}</div>`
      },
    },
    grid: chartGrid(),
    xAxis: {
      ...chartXAxis(times),
      axisLabel: {
        color: CHART_COLORS.muted,
        fontSize: 9,
        interval: Math.max(0, Math.floor(times.length / 8) - 1),
      },
      boundaryGap: false,
    },
    yAxis: {
      ...chartYAxis(),
      min: 0,
      max: 100,
    },
    series: [
      {
        name: '拥堵指数',
        type: 'line',
        data: values,
        smooth: true,
        symbol: 'none',
        lineStyle: { color: CHART_COLORS.cyan, width: 1.8 },
        itemStyle: { color: CHART_COLORS.cyan },
        areaStyle: {
          color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
            { offset: 0, color: 'rgba(0, 212, 255, 0.25)' },
            { offset: 1, color: 'rgba(0, 212, 255, 0.01)' },
          ]),
        },
        animationDuration: 400,
      },
      {
        name: '告警阈值',
        type: 'line',
        data: [],
        markLine: {
          ...thresholdMarkLine(80, '严重拥堵'),
          data: [
            { yAxis: 80, name: '严重拥堵' },
            { yAxis: 60, name: '拥堵' },
          ],
        },
        animation: false,
      },
    ],
  }
}

// ================================================================
// 初始化
// ================================================================
function initBarChart(): void {
  if (!barContainer.value) return
  barChart = echarts.init(barContainer.value)
  barChart.setOption(buildBarOption(compareMetrics.value))
}

function initLineChart(): void {
  if (!lineContainer.value) return
  lineChart = echarts.init(lineContainer.value)
  lineChart.setOption(buildLineOption(congestionTrend.value))
}

function onResize(): void {
  barChart?.resize()
  lineChart?.resize()
}

onMounted(() => {
  initBarChart()
  initLineChart()
  window.addEventListener('resize', onResize)
})

// ---- 响应 store 数据变化 ----
watch(
  compareMetrics,
  (metrics) => {
    barChart?.setOption(buildBarOption(metrics), { notMerge: true })
  },
  { deep: true },
)

watch(
  congestionTrend,
  (trend) => {
    lineChart?.setOption(buildLineOption(trend), { notMerge: true })
  },
  { deep: true },
)

onBeforeUnmount(() => {
  window.removeEventListener('resize', onResize)
  barChart?.dispose()
  lineChart?.dispose()
  barChart = null
  lineChart = null
})
</script>

<template>
  <section class="hud-card data-panel-card comp-card">
    <div class="hud-panel-titlebar">
      <div class="titlebar-inner">
        <span class="titlebar-mark" />
        <span class="titlebar-text">AI 控制效果对比</span>
        <span class="titlebar-deco"><i /><i /><i /></span>
      </div>
    </div>

    <div class="hud-card__content comp-card__body">
      <div class="cc-charts">
        <!-- 左：对比柱状图 -->
        <div class="cc-chart-panel">
          <div class="cc-chart-panel__label">AI 控制前后指标对比</div>
          <div ref="barContainer" class="cc-echart-box" />
        </div>

        <!-- 右：拥堵实时折线图 -->
        <div class="cc-chart-panel">
          <div class="cc-chart-panel__label">拥堵指数实时变化</div>
          <div ref="lineContainer" class="cc-echart-box" />
        </div>
      </div>
    </div>
  </section>
</template>

<style scoped>
.comp-card {
  height: 100%;
  display: flex;
  flex-direction: column;
}

.comp-card__body {
  flex: 1;
  min-height: 0;
  overflow: hidden;
}

/* 两个图表左右排列 */
.cc-charts {
  height: 100%;
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
}

.cc-chart-panel {
  display: flex;
  flex-direction: column;
  min-height: 0;
  min-width: 0;
}

.cc-chart-panel__label {
  flex: 0 0 auto;
  font-size: 12px;
  color: #8da8c5;
  letter-spacing: 0.04em;
  margin-bottom: 4px;
  padding-left: 4px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.cc-echart-box {
  flex: 1;
  min-height: 0;
}
</style>
