<script setup lang="ts">
// ================================================================
// TrafficStats — 交通统计面板
// 从 Pinia store 读取 10 项全局统计指标，实时更新
// ================================================================
import { computed } from 'vue'
import { storeToRefs } from 'pinia'
import { useTrafficStore } from '@/stores/traffic'

const store = useTrafficStore()
const { statistics } = storeToRefs(store)

// ---- 10 项 KPI 指标定义 ----
interface KpiTile {
  label: string
  key: keyof typeof statistics.value
  unit: string
  icon: string
  tone: 'primary' | 'emerald' | 'amber' | 'rose' | 'violet'
  fmt?: (v: number) => string
}

const kpiTiles: KpiTile[] = [
  {
    label: '当前在网车辆',
    key: 'totalFlow',
    unit: '辆',
    icon: '🚗',
    tone: 'primary',
    fmt: (v) => v.toLocaleString(),
  },
  {
    label: '平均通行速度',
    key: 'averageSpeed',
    unit: 'km/h',
    icon: '🏎️',
    tone: 'emerald',
    fmt: (v) => v.toFixed(1),
  },
  {
    label: '平均等待时间',
    key: 'averageWaitTime',
    unit: 's',
    icon: '⏱️',
    tone: 'amber',
    fmt: (v) => v.toFixed(1),
  },
  {
    label: '当前拥堵指数',
    key: 'congestionIndex',
    unit: '/100',
    icon: '📊',
    tone: 'rose',
    fmt: (v) => v.toFixed(1),
  },
  {
    label: '拥堵路段数量',
    key: 'congestedRoadCount',
    unit: '条',
    icon: '🛣️',
    tone: 'amber',
  },
  {
    label: '已优化路口',
    key: 'optimizedIntersectionCount',
    unit: '个',
    icon: '🔀',
    tone: 'emerald',
  },
  {
    label: '应急车辆',
    key: 'emergencyVehicleCount',
    unit: '辆',
    icon: '🚨',
    tone: 'rose',
  },
  {
    label: '设备在线率',
    key: 'deviceOnlineRate',
    unit: '%',
    icon: '📡',
    tone: 'emerald',
    fmt: (v) => v.toFixed(1),
  },
  {
    label: '今日告警数量',
    key: 'todayAlertCount',
    unit: '条',
    icon: '🔔',
    tone: 'rose',
  },
  {
    label: '绿波执行次数',
    key: 'greenWaveCount',
    unit: '次',
    icon: '🌊',
    tone: 'violet',
  },
]

// ---- 饱和度（从 roads 和 intersections 聚合计算） ----
const saturation = computed(() => {
  const dirs = [
    { label: '东西向主干', color: '#FFB800', pattern: /东/ },
    { label: '南北向主干', color: '#22D3A0', pattern: /南/ },
    { label: '环城快速路', color: '#00D4FF', pattern: /环城|快速/ },
    { label: '建设路沿线', color: '#FF4D6D', pattern: /建设/ },
  ]

  return dirs.map((d) => {
    const matched = store.roads.filter((r) => d.pattern.test(r.name))
    if (matched.length === 0) {
      return { name: d.label, value: 0, color: d.color }
    }
    const avg = matched.reduce((sum, r) => sum + r.congestionIndex, 0) / matched.length
    return { name: d.label, value: Math.round(avg), color: d.color }
  })
})
</script>

<template>
  <section class="hud-card data-panel-card comp-card">
    <div class="hud-panel-titlebar">
      <div class="titlebar-inner">
        <span class="titlebar-mark" />
        <span class="titlebar-text">交通统计</span>
        <div class="titlebar-meta">
          <span class="status-dot status-dot--live" />
          <span class="meta-text">实时</span>
        </div>
      </div>
    </div>

    <div class="hud-card__content comp-card__body">
      <!-- 10 项 KPI 2x5 网格 -->
      <div class="ts-kpi-grid">
        <div
          v-for="kpi in kpiTiles"
          :key="kpi.key"
          class="ts-kpi"
          :class="`ts-kpi--${kpi.tone}`"
        >
          <div class="ts-kpi__header">
            <span class="ts-kpi__icon">{{ kpi.icon }}</span>
            <span class="ts-kpi__label">{{ kpi.label }}</span>
          </div>
          <div class="ts-kpi__value-row">
            <span class="kpi-value" :class="`kpi-value--${kpi.tone}`">
              {{ kpi.fmt ? kpi.fmt(statistics[kpi.key] as number) : statistics[kpi.key] }}
            </span>
            <span class="kpi-unit">{{ kpi.unit }}</span>
          </div>
        </div>
      </div>

      <!-- 方向饱和度 -->
      <div class="ts-saturation">
        <div class="ts-saturation__title">路段饱和度</div>
        <div v-for="s in saturation" :key="s.name" class="ts-sat-row">
          <span class="ts-sat-name">{{ s.name }}</span>
          <div class="health-bar ts-sat-bar">
            <div
              class="health-bar-fill"
              :style="{ width: `${s.value}%`, background: s.color }"
            />
          </div>
          <span class="ts-sat-value" :style="{ color: s.color }">{{ s.value }}%</span>
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
  display: flex;
  flex-direction: column;
  gap: 10px;
  overflow-y: auto;
  overflow-x: hidden;
}

/* 标题栏实时标签 */
.titlebar-meta {
  margin-left: auto;
  display: inline-flex;
  align-items: center;
  gap: 5px;
}

.meta-text {
  font-size: 11px;
  color: #22d3a0;
  font-family: 'Rajdhani', sans-serif;
  letter-spacing: 0.06em;
}

/* 10 项 KPI 2 列 */
.ts-kpi-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px;
  flex: 0 0 auto;
}

.ts-kpi {
  padding: 7px 10px;
  background: rgba(0, 212, 255, 0.05);
  border: 1px solid rgba(0, 212, 255, 0.18);
  clip-path: polygon(
    6px 0, 100% 0, 100% calc(100% - 6px),
    calc(100% - 6px) 100%, 0 100%, 0 6px
  );
  transition: border-color 200ms ease, background-color 200ms ease;
}

.ts-kpi:hover {
  border-color: rgba(0, 212, 255, 0.45);
  background: rgba(0, 212, 255, 0.09);
}

/* 颜色边框暗示 */
.ts-kpi--primary { border-left: 2px solid rgba(0, 212, 255, 0.6); }
.ts-kpi--emerald { border-left: 2px solid rgba(34, 211, 160, 0.6); }
.ts-kpi--amber   { border-left: 2px solid rgba(255, 184, 0, 0.6); }
.ts-kpi--rose    { border-left: 2px solid rgba(255, 77, 109, 0.6); }
.ts-kpi--violet  { border-left: 2px solid rgba(124, 92, 255, 0.6); }

.ts-kpi__header {
  display: flex;
  align-items: center;
  gap: 6px;
}

.ts-kpi__icon {
  font-size: 14px;
  flex: 0 0 auto;
}

.ts-kpi__label {
  font-size: 11px;
  color: #8da8c5;
  letter-spacing: 0.03em;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ts-kpi__value-row {
  display: flex;
  align-items: baseline;
  gap: 4px;
  margin-top: 4px;
  overflow: hidden;
}

.ts-kpi .kpi-value {
  font-size: clamp(18px, 1.4vw, 26px);
  line-height: 1.1;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.ts-kpi .kpi-unit {
  font-size: 10px;
  color: #5a7595;
}

/* 饱和度区域 */
.ts-saturation {
  display: flex;
  flex-direction: column;
  gap: 7px;
  flex: 0 0 auto;
}

.ts-saturation__title {
  font-size: 11px;
  color: #8da8c5;
  letter-spacing: 0.06em;
}

.ts-sat-row {
  display: flex;
  align-items: center;
  gap: 8px;
}

.ts-sat-name {
  flex: 0 0 78px;
  font-size: 10px;
  color: #8da8c5;
  text-align: right;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ts-sat-bar {
  flex: 1;
}

.ts-sat-value {
  flex: 0 0 36px;
  text-align: right;
  font-family: 'Rajdhani', sans-serif;
  font-size: 12px;
  font-weight: 700;
}
</style>
