<script setup lang="ts">
// ================================================================
// CompareCharts — 实时拥堵指标
// 左：AI 控制前后指标对比柱状图 | 右：拥堵指数实时变化折线图
// ================================================================
import { computed, onMounted, onBeforeUnmount, watch, ref } from 'vue'
import { storeToRefs } from 'pinia'
import * as echarts from 'echarts'
import type { EChartsOption } from 'echarts'
import { useTrafficStore } from '@/stores/traffic'
import type { CongestionTrendPoint } from '@/types/traffic'
import {
  CHART_COLORS,
  chartTextStyle,
  chartGrid,
  chartTooltip,
  chartLegend,
  chartXAxis,
  chartYAxis,
  thresholdMarkLine,
} from '@/utils/echartsTheme'

const store = useTrafficStore()
const { compareMetrics, congestionTrend, waitTrend, speedTrend } = storeToRefs(store)

const summaryItems = computed(() => {
  const m = compareMetrics.value
  const wt = waitTrend.value
  const st = speedTrend.value
  const curWait = wt.length > 0 ? wt[wt.length - 1]!.value : m.averageWaitTime.ai
  const curSpeed = st.length > 0 ? st[st.length - 1]!.value : m.averageSpeed.ai
  return [
    { label: '当前均等待', value: `${curWait}s` },
    { label: '当前均速', value: `${curSpeed}km/h` },
    { label: '拥堵指数', value: `${congestionTrend.value.length > 0 ? congestionTrend.value[congestionTrend.value.length - 1]!.value : '--'}` },
  ]
})

// ---- DOM 引用 ----
const trendContainer = ref<HTMLDivElement | null>(null)
const lineContainer = ref<HTMLDivElement | null>(null)

let trendChart: echarts.ECharts | null = null
let lineChart: echarts.ECharts | null = null

// ================================================================
// 图表 1：实时指标趋势（均等待 + 均速双线图）
// ================================================================
function buildTrendOption(wt: CongestionTrendPoint[], st: CongestionTrendPoint[], baselineWait: number, baselineSpeed: number): EChartsOption {
  const times = wt.length > 0 ? wt.map((p) => p.time) : ['--']
  const waitVals = wt.length > 0 ? wt.map((p) => p.value) : [0]
  const speedVals = st.length > 0 ? st.map((p) => p.value) : [0]

  return {
    backgroundColor: 'transparent',
    textStyle: { ...chartTextStyle(), fontSize: 14 },
    tooltip: {
      ...chartTooltip(),
      trigger: 'axis',
    },
    legend: {
      ...chartLegend(['均等待(s)', '均速(km/h)']),
      top: 0, left: 'center', right: 'auto',
      textStyle: { color: CHART_COLORS.text, fontSize: 12 },
      itemWidth: 18, itemHeight: 4, itemGap: 14,
    },
    grid: chartGrid({ top: 32, bottom: 26, left: 8, right: 8 }),
    xAxis: {
      ...chartXAxis(times),
      axisLabel: { color: CHART_COLORS.muted, fontSize: 11, interval: Math.max(0, Math.floor(times.length / 5) - 1) },
      boundaryGap: false,
    },
    yAxis: [
      { ...chartYAxis(), position: 'left', splitNumber: 4,
        axisLine: { show: true, lineStyle: { color: 'rgba(0, 212, 255, 0.38)' } },
        axisLabel: { color: CHART_COLORS.cyan, fontSize: 11, margin: 9 },
        // @ts-expect-error ECharts supports markLine here at runtime, but the axis type omits it.
        markLine: baselineWait > 0 ? { silent: true, symbol: 'none', lineStyle: { color: CHART_COLORS.cyan, type: 'dashed' as const, width: 1, opacity: 0.55 }, label: { show: false }, data: [{ yAxis: baselineWait }] } : undefined,
      },
      { ...chartYAxis(), position: 'right', splitNumber: 4, alignTicks: true,
        splitLine: { show: false },
        axisLine: { show: true, lineStyle: { color: 'rgba(255, 184, 0, 0.42)' } },
        axisLabel: { color: CHART_COLORS.amber, fontSize: 11, margin: 9 },
        // @ts-expect-error ECharts supports markLine here at runtime, but the axis type omits it.
        markLine: baselineSpeed > 0 ? { silent: true, symbol: 'none', lineStyle: { color: CHART_COLORS.amber, type: 'dotted' as const, width: 1, opacity: 0.6 }, label: { show: false }, data: [{ yAxis: baselineSpeed }] } : undefined,
      },
    ],
    series: [
      {
        name: '均等待(s)', type: 'line', data: waitVals, smooth: true, symbol: 'none',
        lineStyle: { color: CHART_COLORS.cyan, width: 3 }, itemStyle: { color: CHART_COLORS.cyan },
      },
      {
        name: '均速(km/h)', type: 'line', data: speedVals, smooth: true, symbol: 'none', yAxisIndex: 1,
        lineStyle: { color: CHART_COLORS.amber, width: 2.6 }, itemStyle: { color: CHART_COLORS.amber },
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
    textStyle: { ...chartTextStyle(), fontSize: 14 },
    tooltip: {
      ...chartTooltip(),
      trigger: 'axis',
      formatter: (params: unknown) => {
        const arr = params as Array<{ name: string; value: number }>
        if (!arr[0]) return ''
        return `<div style="font-weight:700;margin-bottom:4px">${arr[0].name}</div><div style="color:${CHART_COLORS.cyan}">拥堵指数: ${arr[0].value}</div>`
      },
    },
    grid: chartGrid({ top: 24, bottom: 30, left: 28, right: 30 }),
    xAxis: {
      ...chartXAxis(times),
      axisLabel: {
        color: CHART_COLORS.muted,
        fontSize: 11,
        fontWeight: 600,
        interval: Math.max(0, Math.floor(times.length / 5) - 1),
      },
      boundaryGap: false,
    },
    yAxis: {
      ...chartYAxis(),
      axisLabel: { color: CHART_COLORS.muted, fontSize: 11 },
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
        lineStyle: { color: CHART_COLORS.cyan, width: 3.4 },
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
function initTrendChart(): void {
  if (!trendContainer.value) return
  trendChart = echarts.init(trendContainer.value)
  const m = compareMetrics.value
  trendChart.setOption(buildTrendOption(waitTrend.value, speedTrend.value, m.averageWaitTime.traditional, m.averageSpeed.traditional))
}

function initLineChart(): void {
  if (!lineContainer.value) return
  lineChart = echarts.init(lineContainer.value)
  lineChart.setOption(buildLineOption(congestionTrend.value))
}

function onResize(): void {
  trendChart?.resize()
  lineChart?.resize()
}

onMounted(() => {
  initTrendChart()
  initLineChart()
  window.addEventListener('resize', onResize)
})

watch([waitTrend, speedTrend], () => {
  const m = compareMetrics.value
  trendChart?.setOption(buildTrendOption(waitTrend.value, speedTrend.value, m.averageWaitTime.traditional, m.averageSpeed.traditional), { notMerge: true })
})

watch(congestionTrend, (trend) => {
  lineChart?.setOption(buildLineOption(trend), { notMerge: true })
}, { deep: true })

onBeforeUnmount(() => {
  window.removeEventListener('resize', onResize)
  trendChart?.dispose()
  lineChart?.dispose()
  trendChart = null
  lineChart = null
})
</script>

<template>
  <section class="hud-card data-panel-card comp-card">
    <div class="hud-panel-titlebar">
      <div class="titlebar-inner">
        <span class="titlebar-mark" />
        <span class="titlebar-text">实时拥堵指标</span>
        <span class="titlebar-deco"><i /><i /><i /></span>
      </div>
    </div>

    <div class="hud-card__content comp-card__body">
      <div class="cc-summary">
        <div v-for="item in summaryItems" :key="item.label" class="cc-summary-item">
          <span>{{ item.label }}</span>
          <b>{{ item.value }}</b>
        </div>
      </div>
      <div class="cc-charts">
        <!-- 左：对比柱状图 -->
        <div class="cc-chart-panel">
          <div class="cc-chart-panel__label">实时指标趋势</div>
          <div ref="trendContainer" class="cc-echart-box" />
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
  container-type: inline-size;
}

.comp-card__body {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  gap: 10px;
  overflow: hidden;
}

.comp-card :deep(.titlebar-text) {
  font-size: 19px;
  letter-spacing: 0;
}

.cc-summary {
  flex: 0 0 auto;
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 5px;
}

.cc-summary-item {
  display: flex;
  min-width: 0;
  align-items: center;
  justify-content: space-between;
  gap: 4px;
  padding: 3px 8px;
  border: 1px solid rgba(0, 212, 255, 0.14);
  background: rgba(2, 18, 33, 0.22);
  color: #8da8c5;
  font-size: 10px;
  clip-path: polygon(8px 0, 100% 0, 100% calc(100% - 8px), calc(100% - 8px) 100%, 0 100%, 0 8px);
}

.cc-summary-item span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; flex-shrink: 1; }

.cc-summary-item b {
  color: #7af7ff;
  font-family: 'Rajdhani', 'DINPro', monospace;
  font-size: 15px;
  font-weight: 800;
  flex-shrink: 0;
}

.cc-charts {
  flex: 1;
  min-height: 0;
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
  font-size: 16px;
  color: #b8e6ff;
  letter-spacing: 0;
  margin-bottom: 5px;
  padding-left: 4px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.cc-echart-box {
  flex: 1;
  min-height: 0;
}

@media (max-width: 900px) {
  .cc-summary,
  .cc-charts {
    grid-template-columns: 1fr;
  }
}

@container (max-width: 520px) {
  .comp-card :deep(.titlebar-text) {
    font-size: 18px;
  }

  .comp-card__body {
    gap: 6px;
  }

  .cc-summary {
    grid-template-columns: repeat(3, minmax(0, 1fr));
    gap: 4px;
  }

  .cc-summary-item {
    align-items: flex-start;
    flex-direction: column;
    gap: 0;
    padding: 3px 5px;
    font-size: 9px;
  }

  .cc-summary-item span {
    width: 100%;
  }

  .cc-summary-item b {
    font-size: 13px;
  }

  .cc-charts {
    grid-template-columns: 1fr;
    grid-template-rows: minmax(0, 1.4fr) minmax(0, 0.9fr);
    gap: 8px;
  }

  .cc-chart-panel__label {
    margin-bottom: 4px;
    font-size: 14px;
  }
}
</style>
